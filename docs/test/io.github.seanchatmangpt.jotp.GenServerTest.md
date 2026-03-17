# io.github.seanchatmangpt.jotp.GenServerTest

## Table of Contents

- [GenServer: Call Timeout Protection](#genservercalltimeoutprotection)
- [GenServer: Asynchronous Cast (Fire-and-Forget)](#genserverasynchronouscastfireandforget)
- [GenServer: Custom Reply Values](#genservercustomreplyvalues)
- [GenServer: Synchronous Request-Reply (call)](#genserversynchronousrequestreplycall)
- [GenServer: Info Messages (Out-of-Band Notifications)](#genserverinfomessagesoutofbandnotifications)
- [GenServer: Graceful Shutdown](#genservergracefulshutdown)
- [GenServer: FIFO Ordering Guarantee](#genserverfifoorderingguarantee)
- [GenServer: Complete Message Type Overview](#genservercompletemessagetypeoverview)


## GenServer: Call Timeout Protection

call() accepts a timeout duration. If the server doesn't respond within the timeout, the call fails. This prevents indefinite blocking.

> [!WARNING]
> Always use timeouts in production to prevent deadlocks. A server that crashes or hangs should not block callers forever.

```java
var server = GenServer.start(0, slowHandler);
var future = server.call(new CounterMsg.Get(), Duration.ofMillis(100));
// Server takes 2000ms, timeout is 100ms -> call fails
```

| Key | Value |
| --- | --- |
| `Result` | `Timeout Exception` |
| `Client Timeout` | `100ms` |
| `Server Response Time` | `2000ms` |

## GenServer: Asynchronous Cast (Fire-and-Forget)

cast() sends messages without waiting for a reply. The sender continues immediately; messages are processed asynchronously by the server.

```java
var handler = new GenServer.Handler<Integer, CounterMsg>() {
    @Override
    public GenServer.CallResult<Integer> handleCall(
            CounterMsg request, Integer state) {
        return switch (request) {
            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
            default -> new GenServer.CallResult<>(state, -1);
        };
    }

    @Override
    public Integer handleCast(CounterMsg request, Integer state) {
        return switch (request) {
            case CounterMsg.Increment _ -> state + 1;
            case CounterMsg.Decrement _ -> state - 1;
            default -> state;
        };
    }

    @Override
    public Integer handleInfo(Object info, Integer state) {
        return state;
    }
};

var server = GenServer.start(0, handler);

// Send multiple cast messages
server.cast(new CounterMsg.Increment());
server.cast(new CounterMsg.Increment());
server.cast(new CounterMsg.Decrement());

// Verify state after async processing
var result = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
// result == 1 (0 + 1 + 1 - 1 = 1)
```

| Key | Value |
| --- | --- |
| `Final State` | `1` |
| `Operations` | `Increment, Increment, Decrement` |
| `Initial State` | `0` |

## GenServer: Custom Reply Values

handleCall returns a CallResult containing (nextState, reply). The reply value is sent back to the caller.

```java
var handler = new GenServer.Handler<String, EchoMsg>() {
    @Override
    public GenServer.CallResult<String> handleCall(EchoMsg request, String state) {
        return switch (request) {
            case EchoMsg.Echo echo -> {
                var reply = "echo:" + echo.value();
                yield new GenServer.CallResult<>(state, reply);
            }
            default -> new GenServer.CallResult<>(state, "");
        };
    }

    @Override
    public String handleCast(EchoMsg request, String state) {
        return state;
    }

    @Override
    public String handleInfo(Object info, String state) {
        return state;
    }
};

var server = GenServer.start("initial", handler);

var reply = server.call(new EchoMsg.Echo("hello"), CALL_TIMEOUT).get();
// reply == "echo:hello"
```

| Key | Value |
| --- | --- |
| `Type` | `String` |
| `State Unchanged` | `true` |
| `Reply` | `echo:hello` |
| `Input` | `hello` |

## GenServer: Synchronous Request-Reply (call)

call() sends a synchronous request and blocks until the server replies. The caller receives the response value from handleCall.

```java
var handler = new GenServer.Handler<Integer, CounterMsg>() {
    @Override
    public GenServer.CallResult<Integer> handleCall(
            CounterMsg request, Integer state) {
        return switch (request) {
            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
            default -> new GenServer.CallResult<>(state, -1);
        };
    }

    @Override
    public Integer handleCast(CounterMsg request, Integer state) {
        return switch (request) {
            case CounterMsg.Increment _ -> state + 1;
            default -> state;
        };
    }

    @Override
    public Integer handleInfo(Object info, Integer state) {
        return state;
    }
};

var server = GenServer.start(42, handler);

var reply = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
// reply == 42
```

| Key | Value |
| --- | --- |
| `Reply Received` | `42` |
| `Initial State` | `42` |

## GenServer: Info Messages (Out-of-Band Notifications)

info() sends out-of-band messages that don't expect a reply. Useful for timers, monitors, and external notifications.

```java
var infoCount = new AtomicInteger(0);

var handler = new GenServer.Handler<Integer, CounterMsg>() {
    @Override
    public GenServer.CallResult<Integer> handleCall(
            CounterMsg request, Integer state) {
        return switch (request) {
            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
            default -> new GenServer.CallResult<>(state, -1);
        };
    }

    @Override
    public Integer handleCast(CounterMsg request, Integer state) {
        return state;
    }

    @Override
    public Integer handleInfo(Object info, Integer state) {
        if (info instanceof String) {
            infoCount.incrementAndGet();
        }
        return state;
    }
};

var server = GenServer.start(0, handler);

// Send info messages
server.info("info1");
server.info("info2");
server.info("info3");

// Wait for info processing
// infoCount.get() == 3
```

| Key | Value |
| --- | --- |
| `Info Messages Processed` | `3` |
| `Info Messages Sent` | `3` |

## GenServer: Graceful Shutdown

stop() gracefully shuts down the server, draining all remaining messages from the mailbox before terminating.

```java
var processedCount = new AtomicInteger(0);

var handler = new GenServer.Handler<Integer, CounterMsg>() {
    @Override
    public GenServer.CallResult<Integer> handleCall(
            CounterMsg request, Integer state) {
        processedCount.incrementAndGet();
        return switch (request) {
            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
            default -> new GenServer.CallResult<>(state, -1);
        };
    }

    @Override
    public Integer handleCast(CounterMsg request, Integer state) {
        processedCount.incrementAndGet();
        return switch (request) {
            case CounterMsg.Increment _ -> state + 1;
            default -> state;
        };
    }

    @Override
    public Integer handleInfo(Object info, Integer state) {
        return state;
    }
};

var server = GenServer.start(0, handler);

// Queue several messages
server.cast(new CounterMsg.Increment());
server.cast(new CounterMsg.Increment());
server.cast(new CounterMsg.Increment());

// Stop and wait for graceful shutdown
server.stop();

// All messages should have been processed
// processedCount.get() == 3
```

| Key | Value |
| --- | --- |
| `Messages Queued` | `3` |
| `Messages Processed` | `3` |

## GenServer: FIFO Ordering Guarantee

GenServer processes cast messages in FIFO order. Messages sent earlier are always processed before messages sent later.

```java
var handler =
    new GenServer.Handler<List<String>, StateMsg>() {
        @Override
        public GenServer.CallResult<List<String>> handleCall(
                StateMsg request, List<String> state) {
            return switch (request) {
                case StateMsg.GetState _ ->
                        new GenServer.CallResult<>(state, new ArrayList<>(state));
                default -> new GenServer.CallResult<>(state, List.of());
            };
        }

        @Override
        public List<String> handleCast(StateMsg request, List<String> state) {
            return switch (request) {
                case StateMsg.Append append -> {
                    var newState = new ArrayList<>(state);
                    newState.add(append.value());
                    yield newState;
                }
                case StateMsg.Clear _ -> new ArrayList<>();
                default -> state;
            };
        }

        @Override
        public List<String> handleInfo(Object info, List<String> state) {
            return state;
        }
    };

var server = GenServer.start(new ArrayList<>(), handler);

// Send multiple casts in order
server.cast(new StateMsg.Append("first"));
server.cast(new StateMsg.Append("second"));
server.cast(new StateMsg.Append("third"));

// Verify ordering
var result = server.call(new StateMsg.GetState(), CALL_TIMEOUT).get();
// result == ["first", "second", "third"]
```

| Key | Value |
| --- | --- |
| `Messages Sent` | `3` |
| `Result` | `[first, second, third]` |
| `Ordering` | `FIFO` |

## GenServer: Complete Message Type Overview

GenServer supports three message types: call (sync request-reply), cast (async fire-and-forget), and info (out-of-band notifications).

| Message Type | Method | Blocking | Reply | Use Case |
| --- | --- | --- | --- | --- |
| Call | call() | Yes (with timeout) | Yes | Request-response pattern |
| Cast | cast() | No | No | Fire-and-forget updates |
| Info | info() | No | No | Timers, monitors, external events |

```java
// Counter server demonstrating all three message types
var handler = new GenServer.Handler<Integer, CounterMsg>() {
    @Override
    public GenServer.CallResult<Integer> handleCall(
            CounterMsg request, Integer state) {
        // Synchronous: returns (nextState, reply)
        return switch (request) {
            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
            default -> new GenServer.CallResult<>(state, -1);
        };
    }

    @Override
    public Integer handleCast(CounterMsg request, Integer state) {
        // Asynchronous: returns nextState only
        return switch (request) {
            case CounterMsg.Increment _ -> state + 1;
            case CounterMsg.Decrement _ -> state - 1;
            case CounterMsg.Reset _ -> 0;
            default -> state;
        };
    }

    @Override
    public Integer handleInfo(Object info, Integer state) {
        // Out-of-band: returns nextState only
        // Log, monitor, or handle external notifications
        return state;
    }
};

var server = GenServer.start(0, handler);

// Cast: async fire-and-forget
server.cast(new CounterMsg.Increment());
server.cast(new CounterMsg.Increment());

// Call: sync request-reply
var count = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
// count == 2

// Info: out-of-band notification
server.info("timer fired");
```

| Key | Value |
| --- | --- |
| `Final State` | `2` |
| `Info Messages` | `1 (timer fired)` |
| `Call Messages` | `1 (Get)` |
| `Cast Messages` | `2 (Increment, Increment)` |

---
*Generated by [DTR](http://www.dtr.org)*
