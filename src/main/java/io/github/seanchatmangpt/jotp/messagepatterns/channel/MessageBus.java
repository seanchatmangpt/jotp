package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Message Bus pattern: a central hub for command dispatching and notification broadcasting.
 *
 * <p>Enterprise Integration Pattern: <em>Message Bus</em> (EIP §9.1). A central coordination point
 * that decouples senders from receivers through two mechanisms: command handlers (one handler per
 * command type) and notification interests (many subscribers per notification type).
 *
 * <p>Erlang analog: a registered {@code gen_server} acting as a name-based dispatch table — {@code
 * whereis(bus) ! {command, CommandType, Payload}} routes to the registered handler.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * TradingBus} maintains separate registries for command handlers and notification subscribers.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var bus = new MessageBus<TradingMessage>();
 *
 * // Register command handler
 * bus.registerCommandHandler("ExecuteBuyOrder", msg -> trader.tell(msg));
 *
 * // Register notification subscriber
 * bus.registerNotificationInterest("BuyOrderExecuted", msg -> portfolio.tell(msg));
 * bus.registerNotificationInterest("BuyOrderExecuted", msg -> analytics.tell(msg));
 *
 * // Dispatch command (to single handler)
 * bus.dispatchCommand("ExecuteBuyOrder", new ExecuteBuyOrder("AAPL", 100));
 *
 * // Broadcast notification (to all interested)
 * bus.broadcastNotification("BuyOrderExecuted", new BuyOrderExecuted("AAPL", 100));
 * }</pre>
 *
 * @param <T> base message type
 */
public final class MessageBus<T> {

    private final ConcurrentHashMap<String, Consumer<T>> commandHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<T>>>
            notificationInterests = new ConcurrentHashMap<>();

    /**
     * Register a command handler for the given command type.
     *
     * @param commandType the command type identifier
     * @param handler the handler that processes commands of this type
     */
    public void registerCommandHandler(String commandType, Consumer<T> handler) {
        commandHandlers.put(commandType, handler);
    }

    /**
     * Register interest in a notification type.
     *
     * @param notificationType the notification type identifier
     * @param subscriber the subscriber to receive notifications
     */
    public void registerNotificationInterest(String notificationType, Consumer<T> subscriber) {
        notificationInterests
                .computeIfAbsent(notificationType, k -> new CopyOnWriteArrayList<>())
                .add(subscriber);
    }

    /**
     * Dispatch a command to its registered handler.
     *
     * @param commandType the command type
     * @param command the command message
     * @return true if a handler was found and invoked
     */
    public boolean dispatchCommand(String commandType, T command) {
        Consumer<T> handler = commandHandlers.get(commandType);
        if (handler != null) {
            handler.accept(command);
            return true;
        }
        return false;
    }

    /**
     * Broadcast a notification to all interested subscribers.
     *
     * @param notificationType the notification type
     * @param notification the notification message
     * @return the number of subscribers notified
     */
    public int broadcastNotification(String notificationType, T notification) {
        var subscribers = notificationInterests.get(notificationType);
        if (subscribers == null) return 0;
        for (Consumer<T> subscriber : subscribers) {
            subscriber.accept(notification);
        }
        return subscribers.size();
    }

    /** Returns the registered command types. */
    public List<String> registeredCommands() {
        return List.copyOf(commandHandlers.keySet());
    }

    /** Returns the registered notification types. */
    public List<String> registeredNotifications() {
        return List.copyOf(notificationInterests.keySet());
    }
}
