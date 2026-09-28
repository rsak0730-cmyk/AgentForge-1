package com.agentforge.app.shizuku;

interface IPrivilegedActions {
    String runAllowed(String action, String payload);
    void destroy();
}
