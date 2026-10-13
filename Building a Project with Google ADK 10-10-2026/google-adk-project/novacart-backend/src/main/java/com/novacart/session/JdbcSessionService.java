package com.novacart.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * A session service backed by a real database.
 *
 * <p>ADK Java does not ship one. The jar has {@code InMemorySessionService} and
 * {@code VertexAiSessionService}, and neither is "a table in my own database".
 *
 * <p>That is not a gap to work around, it is the extension point. {@link BaseSessionService} is
 * an interface with five methods. Implement them and the rest of ADK keeps working, because
 * nothing in the framework knows which implementation it got.
 *
 * <pre>
 *   sessions        one row per conversation, state map as JSON
 *   session_events  one row per message, the whole Event as JSON
 * </pre>
 *
 * <p>Events are stored as JSON rather than shredded into columns: we never query inside one, we
 * only load them in order. H2 here so nothing needs installing; swap the URL for Postgres and
 * not one line below changes.
 */
public class JdbcSessionService implements BaseSessionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String jdbcUrl;

    public JdbcSessionService(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        createTables();
    }

    private void createTables() {
        try (Connection c = connect(); var st = c.createStatement()) {

            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        app_name    VARCHAR(128) NOT NULL,
                        user_id     VARCHAR(128) NOT NULL,
                        session_id  VARCHAR(64)  NOT NULL,
                        state_json  CLOB         NOT NULL,
                        last_update TIMESTAMP    NOT NULL,
                        PRIMARY KEY (app_name, user_id, session_id))""");

            // seq is what keeps the transcript in order. Sorting by a timestamp looks fine until
            // two events land in the same millisecond and the conversation reads backwards.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS session_events (
                        seq        BIGINT AUTO_INCREMENT PRIMARY KEY,
                        app_name   VARCHAR(128) NOT NULL,
                        user_id    VARCHAR(128) NOT NULL,
                        session_id VARCHAR(64)  NOT NULL,
                        event_json CLOB         NOT NULL)""");

            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_events_session
                        ON session_events (app_name, user_id, session_id, seq)""");

        } catch (SQLException e) {
            throw new IllegalStateException("Could not create session tables", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    // ------------------------------------------------------------------ create

    @Override
    public Single<Session> createSession(String appName, String userId,
                                         ConcurrentMap<String, Object> state, String sessionId) {

        String id = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;

        Map<String, Object> initial = state == null ? Map.of() : new HashMap<>(state);
        Instant now = Instant.now();

        return Single.fromCallable(() -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "MERGE INTO sessions (app_name, user_id, session_id, state_json, last_update) "
                                 + "VALUES (?, ?, ?, ?, ?)")) {

                ps.setString(1, appName);
                ps.setString(2, userId);
                ps.setString(3, id);
                ps.setString(4, JSON.writeValueAsString(initial));
                ps.setTimestamp(5, Timestamp.from(now));
                ps.executeUpdate();
            }

            return Session.builder(id)
                    .appName(appName)
                    .userId(userId)
                    .state(new State(new HashMap<>(initial)))
                    .events(new ArrayList<>())
                    .lastUpdateTime(now)
                    .build();
        });
    }

    // ------------------------------------------------------------------ read

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId,
                                     java.util.Optional<GetSessionConfig> config) {

        return Maybe.fromCallable(() -> {
            try (Connection c = connect()) {

                Map<String, Object> state;
                Instant lastUpdate;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT state_json, last_update FROM sessions "
                                + "WHERE app_name = ? AND user_id = ? AND session_id = ?")) {

                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);

                    try (ResultSet rs = ps.executeQuery()) {
                        // Returning null from a Maybe means "empty", which is exactly what a
                        // missing session is. Throwing here would turn a normal miss into an
                        // error the caller has to catch.
                        if (!rs.next()) {
                            return null;
                        }
                        state = readState(rs.getString(1));
                        lastUpdate = rs.getTimestamp(2).toInstant();
                    }
                }

                return Session.builder(sessionId)
                        .appName(appName)
                        .userId(userId)
                        .state(new State(state))
                        .events(loadEvents(c, appName, userId, sessionId))
                        .lastUpdateTime(lastUpdate)
                        .build();
            }
        });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {

        return Single.fromCallable(() -> {
            List<Session> found = new ArrayList<>();

            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT session_id, state_json, last_update FROM sessions "
                                 + "WHERE app_name = ? AND user_id = ? ORDER BY last_update DESC")) {

                ps.setString(1, appName);
                ps.setString(2, userId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // Events are left out on purpose. This method backs a "your past
                        // conversations" list, and loading every transcript to draw a list of
                        // titles is how that screen ends up taking four seconds.
                        found.add(Session.builder(rs.getString(1))
                                .appName(appName)
                                .userId(userId)
                                .state(new State(readState(rs.getString(2))))
                                .events(new ArrayList<>())
                                .lastUpdateTime(rs.getTimestamp(3).toInstant())
                                .build());
                    }
                }
            }

            return ListSessionsResponse.builder().sessions(found).build();
        });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {

        return Single.fromCallable(() -> {
            try (Connection c = connect()) {
                return ListEventsResponse.builder()
                        .events(loadEvents(c, appName, userId, sessionId))
                        .build();
            }
        });
    }

    // ------------------------------------------------------------------ write

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {

        return Completable.fromAction(() -> {
            try (Connection c = connect()) {

                // Events first. The other order leaves orphaned rows if the second delete fails.
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM session_events WHERE app_name = ? AND user_id = ? AND session_id = ?")) {
                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?")) {
                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);
                    ps.executeUpdate();
                }
            }
        });
    }

    /**
     * Called by the runner after every message and every model reply. This is the method that
     * makes the whole thing persistent.
     *
     * <p>The interface provides a default that updates the in-memory Session: it appends to
     * {@code events} and applies the event's state delta. We let it do that first, then write
     * both to the database. Skipping the default would leave the caller's Session object stale
     * for the rest of the turn.
     */
    @Override
    public Single<Event> appendEvent(Session session, Event event) {

        return BaseSessionService.super.appendEvent(session, event)
                .map(appended -> {

                    try (Connection c = connect()) {

                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO session_events (app_name, user_id, session_id, event_json) "
                                        + "VALUES (?, ?, ?, ?)")) {

                            ps.setString(1, session.appName());
                            ps.setString(2, session.userId());
                            ps.setString(3, session.id());
                            ps.setString(4, appended.toJson());
                            ps.executeUpdate();
                        }

                        // The state map is rewritten whole rather than patched. A state map is a
                        // handful of small keys, so this is cheaper than tracking deltas in SQL
                        // and it cannot drift out of sync with the session object.
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE sessions SET state_json = ?, last_update = ? "
                                        + "WHERE app_name = ? AND user_id = ? AND session_id = ?")) {

                            Map<String, Object> plain = new HashMap<>();
                            session.state().forEach(plain::put);

                            ps.setString(1, JSON.writeValueAsString(plain));
                            ps.setTimestamp(2, Timestamp.from(Instant.now()));
                            ps.setString(3, session.appName());
                            ps.setString(4, session.userId());
                            ps.setString(5, session.id());
                            ps.executeUpdate();
                        }
                    }

                    return appended;
                });
    }

    // ------------------------------------------------------------------ helpers

    private static List<Event> loadEvents(Connection c, String appName, String userId,
                                          String sessionId) throws SQLException {

        List<Event> events = new ArrayList<>();

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_json FROM session_events "
                        + "WHERE app_name = ? AND user_id = ? AND session_id = ? ORDER BY seq")) {

            ps.setString(1, appName);
            ps.setString(2, userId);
            ps.setString(3, sessionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(Event.fromJson(rs.getString(1)));
                }
            }
        }

        return events;
    }

    private static Map<String, Object> readState(String json) {
        try {
            return JSON.readValue(json, new TypeReference<HashMap<String, Object>>() {});
        } catch (Exception e) {
            // A corrupt row should not take the whole conversation down. Losing the state map is
            // recoverable; losing the transcript is not.
            return new HashMap<>();
        }
    }
}
