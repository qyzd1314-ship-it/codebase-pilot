package com.yupi.yuaiagent.task.agent;

public interface Agent {

    String name();

    AgentResult run(AgentContext context);
}
