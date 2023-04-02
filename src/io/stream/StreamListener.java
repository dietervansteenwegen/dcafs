package io.stream;

/**
 * Allow a stream to give feedback to the class managing the stream (fe. StreamManager)
 */
public interface StreamListener {
    
    void notifyIdle(BaseStream stream);     // Notify that a stream has been idle
    boolean notifyActive(String id);   // Notify that a stream has become active
    void notifyOpened(String id);   // Notify that the stream connection was opened
    void notifyClosed(String id);   // Notify that the stream connection was closed

    boolean requestReconnection(String id); // Request that the stream is reconnected
}