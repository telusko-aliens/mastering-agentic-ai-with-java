package com.telusko;

import com.google.adk.web.AdkWebServer;

public class WebUi
{
    public static void main(String[] args) {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println("Web UI: http://localhost:8080/dev-ui   (development only)");
        System.out.println();
        AdkWebServer.start(ClassAssistant.ROOT_AGENT);
    }
}
