package com.yupi.codebasepilot.task.agent;

public interface Agent {

    String name();

    AgentResult run(AgentContext context);
}
