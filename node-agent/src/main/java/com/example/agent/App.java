package com.example.agent;

import java.io.IOException;

import com.example.agent.node.NodeAgent;

public class App {

    public static void main(String[] args) {
        try {
            NodeAgent agent = new NodeAgent(args);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}
