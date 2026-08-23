package com.example.walletviewer;

import org.jboss.logmanager.ExtLogRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures the configured JBoss LogManager backend without changing global log levels. */
final class TestLogCapture implements AutoCloseable {
    record Entry(int level, String message, Throwable thrown) { }

    private final Logger logger;
    private final Level previous;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) {
            entries.add(new Entry(record.getLevel().intValue(),
                    ExtLogRecord.wrap(record).getFormattedMessage(), record.getThrown()));
        }
        @Override public void flush() { }
        @Override public void close() { }
    };

    TestLogCapture(Class<?> category) {
        logger = Logger.getLogger(category.getName());
        previous = logger.getLevel();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
    }

    List<Entry> entries() { return List.copyOf(entries); }
    List<String> messages() { return entries.stream().map(Entry::message).toList(); }

    @Override public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previous);
    }
}