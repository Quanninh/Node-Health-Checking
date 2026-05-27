package com.monitoring.agent;

import java.io.IOException;

import com.monitoring.agent.node.NodeAgent;

public class App {

    public static void main(String[] args) {
        try {
            NodeAgent agent = new NodeAgent(args);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
