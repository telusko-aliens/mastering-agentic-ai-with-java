package com.telusko;



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
 * JDBC SESSION SERVICE
 * --------------------
 *
 * ADK needs somewhere to store conversations.
 *
 * Earlier examples use:
 *
 *     InMemorySessionService
 *
 * which means:
 *
 *     JVM stops
 *        ↓
 *     sessions disappear
 *
 *
 * Here we implement:
 *
 *     BaseSessionService
 *
 * ourselves and store sessions in an H2 database.
 *
 *
 * IMPORTANT ARCHITECTURE IDEA
 * ---------------------------
 *
 * Runner depends on:
 *
 *     BaseSessionService
 *
 * not:
 *
 *     InMemorySessionService
 *
 * Therefore we can replace:
 *
 *     InMemorySessionService
 *
 * with:
 *
 *     JdbcSessionService
 *
 * without changing our agent.
 *
 *
 * DATABASE TABLES
 * ---------------
 *
 * sessions
 *
 *     one row per conversation
 *
 *     stores:
 *       - app
 *       - user
 *       - session id
 *       - state
 *
 *
 * session_events
 *
 *     stores the conversation events/transcript
 *
 * Events are stored as JSON because an ADK Event can contain:
 *
 * - text
 * - tool calls
 * - tool results
 * - metadata
 * - token information
 * - flags
 *
 * We do not need SQL columns for every internal Event field.
 */
public class JdbcSessionService
        implements BaseSessionService {

    /**
     * Used for converting state Map ↔ JSON.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper();

    /**
     * Example:
     *
     * jdbc:h2:file:.../data/sessions
     */
    private final String jdbcUrl;

    /**
     * Create the service and make sure
     * the database tables exist.
     */
    public JdbcSessionService(String jdbcUrl) {

        this.jdbcUrl = jdbcUrl;

        createTables();
    }

    /**
     * Create database schema.
     */
    private void createTables() {

        try (
                Connection c = connect();
                var st = c.createStatement()
        ) {

            /*
             * One row per session.
             *
             * state_json contains session/user/app state.
             */
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        app_name    VARCHAR(128) NOT NULL,
                        user_id     VARCHAR(128) NOT NULL,
                        session_id  VARCHAR(64)  NOT NULL,
                        state_json  CLOB         NOT NULL,
                        last_update TIMESTAMP    NOT NULL,
                        PRIMARY KEY (app_name, user_id, session_id))""");

            /*
             * One row per event.
             *
             * seq AUTO_INCREMENT is important because
             * conversation order must be deterministic.
             *
             * Timestamps alone are not ideal:
             *
             * two events could theoretically receive
             * the same timestamp.
             */
            st.execute("""
                    CREATE TABLE IF NOT EXISTS session_events (
                        seq        BIGINT AUTO_INCREMENT PRIMARY KEY,
                        app_name   VARCHAR(128) NOT NULL,
                        user_id    VARCHAR(128) NOT NULL,
                        session_id VARCHAR(64)  NOT NULL,
                        event_json CLOB         NOT NULL)""");

            /*
             * Helps efficiently load all events
             * belonging to one conversation.
             */
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_events_session
                        ON session_events (app_name, user_id, session_id, seq)""");

        } catch (SQLException e) {

            throw new IllegalStateException(
                    "Could not create session tables",
                    e
            );
        }
    }

    /**
     * Open a JDBC connection.
     */
    private Connection connect()
            throws SQLException {

        return DriverManager.getConnection(
                jdbcUrl
        );
    }

    // ============================================================
    // CREATE SESSION
    // ============================================================

    /**
     * Creates a new conversation.
     */
    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            ConcurrentMap<String, Object> state,
            String sessionId) {

        /*
         * If the caller did not provide an id,
         * create a random session id.
         */
        String id =
                (sessionId == null
                        || sessionId.isBlank())

                        ? UUID.randomUUID().toString()

                        : sessionId;

        /*
         * Convert initial state into a normal map.
         */
        Map<String, Object> initial =
                state == null
                        ? Map.of()
                        : new HashMap<>(state);

        Instant now =
                Instant.now();

        /*
         * ADK session APIs use RxJava types.
         *
         * Single = asynchronous operation that
         * eventually returns exactly one result.
         */
        return Single.fromCallable(() -> {

            try (
                    Connection c = connect();

                    PreparedStatement ps =
                            c.prepareStatement(
                                    "MERGE INTO sessions "
                                            + "(app_name, user_id, session_id, "
                                            + "state_json, last_update) "
                                            + "VALUES (?, ?, ?, ?, ?)"
                            )
            ) {

                ps.setString(1, appName);
                ps.setString(2, userId);
                ps.setString(3, id);

                ps.setString(
                        4,
                        JSON.writeValueAsString(
                                initial
                        )
                );

                ps.setTimestamp(
                        5,
                        Timestamp.from(now)
                );

                ps.executeUpdate();
            }

            /*
             * Return an ADK Session object.
             */
            return Session.builder(id)
                    .appName(appName)
                    .userId(userId)
                    .state(
                            new State(
                                    new HashMap<>(
                                            initial
                                    )
                            )
                    )
                    .events(
                            new ArrayList<>()
                    )
                    .lastUpdateTime(now)
                    .build();
        });
    }

    // ============================================================
    // GET ONE SESSION
    // ============================================================

    /**
     * Loads one session from the database.
     *
     * Maybe means:
     *
     *     result may exist
     *
     * or:
     *
     *     result may be empty
     */
    @Override
    public Maybe<Session> getSession(
            String appName,
            String userId,
            String sessionId,
            java.util.Optional<GetSessionConfig> config) {

        return Maybe.fromCallable(() -> {

            try (Connection c = connect()) {

                Map<String, Object> state;
                Instant lastUpdate;

                /*
                 * First load basic session information.
                 */
                try (
                        PreparedStatement ps =
                                c.prepareStatement(
                                        "SELECT state_json, last_update "
                                                + "FROM sessions "
                                                + "WHERE app_name = ? "
                                                + "AND user_id = ? "
                                                + "AND session_id = ?"
                                )
                ) {

                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);

                    try (
                            ResultSet rs =
                                    ps.executeQuery()
                    ) {

                        /*
                         * null from Maybe.fromCallable()
                         * represents "no value".
                         *
                         * Missing session is not an exceptional condition.
                         */
                        if (!rs.next()) {
                            return null;
                        }

                        state =
                                readState(
                                        rs.getString(1)
                                );

                        lastUpdate =
                                rs.getTimestamp(2)
                                        .toInstant();
                    }
                }

                /*
                 * Build Session including its transcript.
                 */
                return Session.builder(sessionId)
                        .appName(appName)
                        .userId(userId)
                        .state(
                                new State(state)
                        )
                        .events(
                                loadEvents(
                                        c,
                                        appName,
                                        userId,
                                        sessionId
                                )
                        )
                        .lastUpdateTime(lastUpdate)
                        .build();
            }
        });
    }

    // ============================================================
    // LIST SESSIONS
    // ============================================================

    /**
     * Returns all conversations belonging to one user.
     *
     * Useful for screens such as:
     *
     *     "Your previous conversations"
     */
    @Override
    public Single<ListSessionsResponse> listSessions(
            String appName,
            String userId) {

        return Single.fromCallable(() -> {

            List<Session> found =
                    new ArrayList<>();

            try (
                    Connection c = connect();

                    PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT session_id, state_json, last_update "
                                            + "FROM sessions "
                                            + "WHERE app_name = ? "
                                            + "AND user_id = ? "
                                            + "ORDER BY last_update DESC"
                            )
            ) {

                ps.setString(1, appName);
                ps.setString(2, userId);

                try (
                        ResultSet rs =
                                ps.executeQuery()
                ) {

                    while (rs.next()) {

                        /*
                         * We deliberately do NOT load every event here.
                         *
                         * For a list of conversations,
                         * loading every full transcript would be expensive.
                         */
                        found.add(
                                Session.builder(
                                                rs.getString(1)
                                        )
                                        .appName(appName)
                                        .userId(userId)
                                        .state(
                                                new State(
                                                        readState(
                                                                rs.getString(2)
                                                        )
                                                )
                                        )
                                        .events(
                                                new ArrayList<>()
                                        )
                                        .lastUpdateTime(
                                                rs.getTimestamp(3)
                                                        .toInstant()
                                        )
                                        .build()
                        );
                    }
                }
            }

            return ListSessionsResponse.builder()
                    .sessions(found)
                    .build();
        });
    }

    // ============================================================
    // LIST EVENTS
    // ============================================================

    /**
     * Returns the transcript/events for one session.
     */
    @Override
    public Single<ListEventsResponse> listEvents(
            String appName,
            String userId,
            String sessionId) {

        return Single.fromCallable(() -> {

            try (Connection c = connect()) {

                return ListEventsResponse.builder()
                        .events(
                                loadEvents(
                                        c,
                                        appName,
                                        userId,
                                        sessionId
                                )
                        )
                        .build();
            }
        });
    }

    // ============================================================
    // DELETE SESSION
    // ============================================================

    /**
     * Deletes a conversation and its events.
     */
    @Override
    public Completable deleteSession(
            String appName,
            String userId,
            String sessionId) {

        return Completable.fromAction(() -> {

            try (Connection c = connect()) {

                /*
                 * First remove child event rows.
                 */
                try (
                        PreparedStatement ps =
                                c.prepareStatement(
                                        "DELETE FROM session_events "
                                                + "WHERE app_name = ? "
                                                + "AND user_id = ? "
                                                + "AND session_id = ?"
                                )
                ) {

                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);

                    ps.executeUpdate();
                }

                /*
                 * Then remove the session itself.
                 */
                try (
                        PreparedStatement ps =
                                c.prepareStatement(
                                        "DELETE FROM sessions "
                                                + "WHERE app_name = ? "
                                                + "AND user_id = ? "
                                                + "AND session_id = ?"
                                )
                ) {

                    ps.setString(1, appName);
                    ps.setString(2, userId);
                    ps.setString(3, sessionId);

                    ps.executeUpdate();
                }
            }
        });
    }

    // ============================================================
    // APPEND EVENT
    // ============================================================

    /**
     * This is the most important persistence method.
     *
     * ADK calls appendEvent whenever new conversation events occur.
     *
     * Examples:
     *
     * - user message
     * - model reply
     * - tool call
     * - tool result
     *
     *
     * First we call:
     *
     *     BaseSessionService.super.appendEvent(...)
     *
     * Why?
     *
     * The default implementation updates the current Session object:
     *
     * - adds the event
     * - applies state changes
     *
     * After that we persist the updated information to JDBC.
     */
    @Override
    public Single<Event> appendEvent(
            Session session,
            Event event) {

        return BaseSessionService.super
                .appendEvent(
                        session,
                        event
                )
                .map(appended -> {

                    try (Connection c = connect()) {

                        /*
                         * Save the event/transcript item.
                         */
                        try (
                                PreparedStatement ps =
                                        c.prepareStatement(
                                                "INSERT INTO session_events "
                                                        + "(app_name, user_id, session_id, event_json) "
                                                        + "VALUES (?, ?, ?, ?)"
                                        )
                        ) {

                            ps.setString(
                                    1,
                                    session.appName()
                            );

                            ps.setString(
                                    2,
                                    session.userId()
                            );

                            ps.setString(
                                    3,
                                    session.id()
                            );

                            ps.setString(
                                    4,
                                    appended.toJson()
                            );

                            ps.executeUpdate();
                        }

                        /*
                         * Also persist the latest State.
                         *
                         * For this simple demo we write the whole state map.
                         *
                         * State is generally small,
                         * so this keeps the implementation simple.
                         */
                        try (
                                PreparedStatement ps =
                                        c.prepareStatement(
                                                "UPDATE sessions "
                                                        + "SET state_json = ?, last_update = ? "
                                                        + "WHERE app_name = ? "
                                                        + "AND user_id = ? "
                                                        + "AND session_id = ?"
                                        )
                        ) {

                            Map<String, Object> plain =
                                    new HashMap<>();

                            session.state()
                                    .forEach(
                                            plain::put
                                    );

                            ps.setString(
                                    1,
                                    JSON.writeValueAsString(
                                            plain
                                    )
                            );

                            ps.setTimestamp(
                                    2,
                                    Timestamp.from(
                                            Instant.now()
                                    )
                            );

                            ps.setString(
                                    3,
                                    session.appName()
                            );

                            ps.setString(
                                    4,
                                    session.userId()
                            );

                            ps.setString(
                                    5,
                                    session.id()
                            );

                            ps.executeUpdate();
                        }
                    }

                    return appended;
                });
    }

    // ============================================================
    // HELPER: LOAD EVENTS
    // ============================================================

    /**
     * Loads conversation events in the exact order
     * in which they were stored.
     */
    private static List<Event> loadEvents(
            Connection c,
            String appName,
            String userId,
            String sessionId)
            throws SQLException {

        List<Event> events =
                new ArrayList<>();

        try (
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT event_json "
                                        + "FROM session_events "
                                        + "WHERE app_name = ? "
                                        + "AND user_id = ? "
                                        + "AND session_id = ? "
                                        + "ORDER BY seq"
                        )
        ) {

            ps.setString(1, appName);
            ps.setString(2, userId);
            ps.setString(3, sessionId);

            try (
                    ResultSet rs =
                            ps.executeQuery()
            ) {

                while (rs.next()) {

                    /*
                     * Convert JSON back into an ADK Event.
                     */
                    events.add(
                            Event.fromJson(
                                    rs.getString(1)
                            )
                    );
                }
            }
        }

        return events;
    }

    // ============================================================
    // HELPER: READ STATE JSON
    // ============================================================

    /**
     * Converts JSON stored in the database back into a Map.
     */
    private static Map<String, Object> readState(
            String json) {

        try {

            return JSON.readValue(
                    json,
                    new TypeReference<
                            HashMap<String, Object>>() {
                    }
            );

        } catch (Exception e) {

            /*
             * For this teaching implementation,
             * recover with an empty state if state JSON is corrupt.
             */
            return new HashMap<>();
        }
    }
}