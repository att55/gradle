/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.launcher.daemon.diagnostics;

import org.gradle.util.GFileUtils;

import java.io.File;
import java.io.Serializable;

/**
 * Contains some daemon diagnostics information useful for the client.
 * <p>
 * by Szczepan Faber, created at: 2/28/12
 */
public class DaemonDiagnostics implements Serializable {

    private final Long pid;
    private final File daemonLog;

    public DaemonDiagnostics(File daemonLog, Long pid) {
        this.daemonLog = daemonLog;
        this.pid = pid;
    }

    /**
     * @return pid. Can be null, it means the daemon was not able to identify its pid.
     */
    public Long getPid() {
        return pid;
    }

    public File getDaemonLog() {
        return daemonLog;
    }

    @Override
    public String toString() {
        return "{"
                + "pid=" + pid
                + ", daemonLog=" + daemonLog
                + '}';
    }

    public String tailDaemonLog() {
        //TODO SF - make safe
        return "----- Daemon log file tail - " + getDaemonLog().getName() + " -----\n"
            + GFileUtils.tail(getDaemonLog(), 20)
            + "\n----- End of daemon log -----\n";
    }

    public String describe() {
        return "Daemon pid: " + pid + "\n"
             + "  log file: " + daemonLog + "\n"
             + tailDaemonLog();
    }
}
