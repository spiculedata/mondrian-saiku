/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects the log records a named logger emits, for tests that assert on
 * what Mondrian logged.
 *
 * <p>Mondrian logs through SLF4J to Log4j 2. Attaching an {@code
 * org.apache.log4j.AppenderSkeleton} to an {@code org.apache.log4j.Logger}
 * -- the Log4j 1 API, which is on the classpath only as a compatibility
 * bridge -- silently captures nothing, so a test written that way passes its
 * "was it logged" assertion vacuously or fails with a zero count that looks
 * like a product bug.
 *
 * <p>Typical use:
 *
 * <blockquote><pre>
 * LogCapture capture = LogCapture.attach(RolapUtil.class.getName());
 * try {
 *     ... run the query ...
 *     assertEquals(1, capture.count(Level.ERROR, "Unable to use native"));
 * } finally {
 *     capture.detach();
 * }
 * </pre></blockquote>
 *
 * <p>Attaching also lowers the logger's level for the duration if it would
 * otherwise filter the records out, and restores it on {@link #detach}.
 */
public class LogCapture {

    private final List<LogEvent> events =
        Collections.synchronizedList(new ArrayList<LogEvent>());
    private final LoggerContext context;
    private final LoggerConfig loggerConfig;
    private final Level priorLevel;
    private final CollectingAppender appender;
    private boolean attached = true;

    private LogCapture(
        LoggerContext context,
        LoggerConfig loggerConfig,
        Level priorLevel,
        CollectingAppender appender)
    {
        this.context = context;
        this.loggerConfig = loggerConfig;
        this.priorLevel = priorLevel;
        this.appender = appender;
    }

    /**
     * Starts collecting records from a logger, at {@code TRACE} or finer.
     *
     * @param loggerName Logger name, usually a class name
     * @return capture, which the caller must {@link #detach}
     */
    public static LogCapture attach(String loggerName) {
        return attach(loggerName, Level.TRACE);
    }

    /**
     * Starts collecting records from a logger.
     *
     * @param loggerName Logger name, usually a class name
     * @param level Lowest level to collect
     * @return capture, which the caller must {@link #detach}
     */
    public static LogCapture attach(String loggerName, Level level) {
        final LoggerContext context =
            (LoggerContext) org.apache.logging.log4j.LogManager
                .getContext(false);
        final Configuration config = context.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);
        if (!loggerConfig.getName().equals(loggerName)) {
            // The logger inherits a parent's config. Give it one of its own,
            // so raising its level and adding the appender does not affect
            // every other logger under that parent.
            final LoggerConfig own =
                new LoggerConfig(loggerName, level, true);
            config.addLogger(loggerName, own);
            loggerConfig = own;
        }
        final Level priorLevel = loggerConfig.getLevel();
        final CollectingAppender appender = new CollectingAppender();
        appender.start();
        final LogCapture capture =
            new LogCapture(context, loggerConfig, priorLevel, appender);
        appender.target = capture.events;
        loggerConfig.addAppender(appender, level, null);
        if (priorLevel == null || !priorLevel.isLessSpecificThan(level)) {
            loggerConfig.setLevel(level);
        }
        context.updateLoggers();
        return capture;
    }

    /**
     * Stops collecting and restores the logger's level. Idempotent, so it is
     * safe in a finally block that may run twice.
     */
    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(priorLevel);
        appender.stop();
        context.updateLoggers();
    }

    /** Discards everything collected so far. */
    public void clear() {
        events.clear();
    }

    /**
     * @param level Level to match exactly
     * @param substring Text the message must contain
     * @return how many collected records match both
     */
    public int count(Level level, String substring) {
        int n = 0;
        synchronized (events) {
            for (LogEvent event : events) {
                if (!event.getLevel().equals(level)) {
                    continue;
                }
                if (!event.getMessage().getFormattedMessage()
                    .contains(substring))
                {
                    continue;
                }
                ++n;
            }
        }
        return n;
    }

    /**
     * @return every collected record, formatted "LEVEL message", for use in
     *     assertion failure messages
     */
    public List<String> describe() {
        final List<String> out = new ArrayList<String>();
        synchronized (events) {
            for (LogEvent event : events) {
                out.add(
                    event.getLevel() + " "
                    + event.getMessage().getFormattedMessage());
            }
        }
        return out;
    }

    private static class CollectingAppender extends AbstractAppender {
        private List<LogEvent> target;

        CollectingAppender() {
            super(
                "LogCapture-" + System.identityHashCode(new Object()),
                (Filter) null, null, true, Property.EMPTY_ARRAY);
        }

        public void append(LogEvent event) {
            if (target != null) {
                target.add(event.toImmutable());
            }
        }
    }
}

// End LogCapture.java
