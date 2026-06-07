package mondrian.calcite;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class MondrianBackendTest {
    @AfterEach public void clear() { System.clearProperty("mondrian.backend"); }

    @Test public void defaultsToCalcite() {
        assertSame(MondrianBackend.CALCITE, MondrianBackend.current());
    }
    @Test public void calcitePropertyPicksCalcite() {
        System.setProperty("mondrian.backend", "calcite");
        assertSame(MondrianBackend.CALCITE, MondrianBackend.current());
    }
    @Test public void unknownFallsBackToLegacyWithWarning() {
        System.setProperty("mondrian.backend", "bogus");
        assertSame(MondrianBackend.LEGACY, MondrianBackend.current());
    }
    @Test public void caseInsensitive() {
        System.setProperty("mondrian.backend", "CALCITE");
        assertSame(MondrianBackend.CALCITE, MondrianBackend.current());
    }

    /** A misconfigured (typo'd) backend value must not be swallowed silently:
     * it falls back to LEGACY, but a WARN naming the bad value is logged so an
     * operator can see the backend choice was ignored. Uses a unique value so
     * the warn-once dedupe does not suppress it on a shared JVM. */
    @Test public void unknownValueLogsWarning() {
        final String bad = "typo-" + Long.toString(System.nanoTime(), 36);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final org.apache.logging.log4j.core.Logger log =
            ctx.getLogger(MondrianBackend.class.getName());
        final org.apache.logging.log4j.Level priorLevel = log.getLevel();
        final CapturingAppender app = new CapturingAppender();
        app.start();
        log.addAppender(app);
        log.setLevel(org.apache.logging.log4j.Level.WARN);
        try {
            System.setProperty("mondrian.backend", bad);
            assertSame(MondrianBackend.LEGACY, MondrianBackend.current());
            assertTrue(
                "expected a WARN naming the unrecognized backend value '" + bad
                    + "', got: " + app.messages,
                app.messages.stream().anyMatch(m -> m.contains(bad)));
        } finally {
            log.setLevel(priorLevel);
            log.removeAppender(app);
            app.stop();
        }
    }

    @Test public void parseRecognizesValidValuesAndTrims() {
        assertSame(MondrianBackend.CALCITE, MondrianBackend.parse("calcite"));
        assertSame(MondrianBackend.LEGACY, MondrianBackend.parse("LEGACY"));
        assertSame(MondrianBackend.CALCITE, MondrianBackend.parse(" calcite "));
    }
    @Test public void parseReturnsNullForUnrecognized() {
        assertNull(MondrianBackend.parse("bogus"));
        assertNull(MondrianBackend.parse(""));
        assertNull(MondrianBackend.parse(null));
    }

    /** Minimal in-memory log4j2 appender capturing formatted messages. */
    private static final class CapturingAppender extends AbstractAppender {
        final List<String> messages = new CopyOnWriteArrayList<>();
        CapturingAppender() {
            super("capturing", null, null, false, Property.EMPTY_ARRAY);
        }
        @Override public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
