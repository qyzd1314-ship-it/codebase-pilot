package com.yupi.yuaiagent.task.agent;

public enum NextAction {
    CONTINUE,
    RETRY,
    REPLAN,
    NEED_HUMAN_APPROVAL,
    DELIVER,
    FAIL
}
