/*
 * Copyright 2021 the original author or authors.
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
package org.mvndaemon.mvnd.sync;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Signal;
import sun.misc.SignalHandler;

public class SyncServer {

    private ServerSocket serverSocket;
    private volatile long lastUsed;
    private boolean closing;
    private AtomicInteger counter = new AtomicInteger();

    private Map<String, Repo> repos = new ConcurrentHashMap<>();
    private Map<String, Context> contexts = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        // When spawning a new process, the child process is create within
        // the same process group.  This means that a few signals are sent
        // to the whole group.  This is the case for SIGINT (Ctrl-C) and
        // SIGTSTP (Ctrl-Z) which are both sent to all the processed in the
        // group when initiated from the controlling terminal.
        // This is only a problem when the client creates the daemon, but
        // without ignoring those signals, a client being interrupted will
        // also interrupt and kill the daemon.
        try {
            Signal.handle(new Signal("INT"), SignalHandler.SIG_IGN);
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Signal.handle(new Signal("TSTP"), SignalHandler.SIG_IGN);
            }
        } catch (Throwable t) {
            System.err.println("Unable to ignore INT and TSTP signals");
            t.printStackTrace();
        }

        int tmpPort = Integer.parseInt(args[0]);
        long rand = Long.parseLong(args[1]);

        SyncServer server = new SyncServer();
        new Thread(server::run).start();
        int port = server.getPort();

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), tmpPort));
            try (DataOutputStream dos = new DataOutputStream(s.getOutputStream())) {
                dos.writeLong(rand);
                dos.writeLong(ProcessHandle.current().pid());
                dos.writeInt(port);
                dos.flush();
            }
        }
    }

    public SyncServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void run() {
        try {
            System.out.println("SyncServer started");
            use();
            run(this::expirationCheck);
            while (true) {
                Socket socket = this.serverSocket.accept();
                run(() -> client(socket));
            }
        } catch (Throwable t) {
            if (!closing) {
                System.err.println("Error running sync server loop");
                t.printStackTrace();
            }
        }
    }

    private void run(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.start();
    }

    private void client(Socket socket) {
        System.out.println("Client connected");
        use();
        Map<String, Context> clientContexts = new ConcurrentHashMap<>();
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            while (true) {
                int requestId = input.readInt();
                int sz = input.readInt();
                List<String> request = new ArrayList<>(sz);
                for (int i = 0; i < sz; i++) {
                    request.add(input.readUTF());
                }
                if (request.isEmpty()) {
                    throw new IOException("Received invalid request");
                }
                switch (request.get(0)) {
                case MvndSyncContextFactory.REQUEST_CONTEXT: {
                    if (request.size() < 3) {
                        return;
                    }
                    String repo = request.get(1);
                    boolean shared = Boolean.parseBoolean(request.get(2));
                    Context context = newContext(repo, shared);
                    contexts.put(context.id, context);
                    clientContexts.put(context.id, context);
                    synchronized (output) {
                        output.writeInt(requestId);
                        output.writeInt(2);
                        output.writeUTF(MvndSyncContextFactory.RESPONSE_CONTEXT);
                        output.writeUTF(context.id);
                        output.flush();
                    }
                    break;
                }
                case MvndSyncContextFactory.REQUEST_ACQUIRE: {
                    String contextId = request.get(1);
                    Context context = contexts.get(contextId);
                    if (context == null) {
                        return;
                    }
                    List<String> keys = request.subList(2, request.size());
                    context.lock(keys).thenRun(() -> {
                        try {
                            synchronized (output) {
                                output.writeInt(requestId);
                                output.writeInt(1);
                                output.writeUTF(MvndSyncContextFactory.RESPONSE_ACQUIRE);
                                output.flush();
                            }
                        } catch (IOException e) {
                            try {
                                socket.close();
                            } catch (IOException ioException) {
                                // ignore
                            }
                        }
                    });
                    break;
                }
                case MvndSyncContextFactory.REQUEST_CLOSE: {
                    String contextId = request.get(1);
                    Context context = contexts.remove(contextId);
                    clientContexts.remove(contextId);
                    if (context == null) {
                        return;
                    }
                    context.unlock();
                    synchronized (output) {
                        output.writeInt(requestId);
                        output.writeInt(1);
                        output.writeUTF(MvndSyncContextFactory.RESPONSE_CLOSE);
                        output.flush();
                    }
                    break;
                }
                }
            }
        } catch (Throwable t) {
            System.err.println("Error reading request");
            t.printStackTrace();
        } finally {
            clientContexts.values().forEach(Context::unlock);
        }
    }

    public Context newContext(String repo, boolean shared) {
        String contextId = String.format("%08x", counter.incrementAndGet());
        return new Context(repos.computeIfAbsent(repo, Repo::new), contextId, shared);
    }

    static class Waiter {
        final Context context;
        final CompletableFuture<Void> future;

        public Waiter(Context context, CompletableFuture<Void> future) {
            this.context = context;
            this.future = future;
        }
    }

    static class Lock {

        final String key;

        List<Context> holders;
        List<Waiter> waiters;

        public Lock(String key) {
            this.key = key;
        }

        public synchronized CompletableFuture<Void> lock(Context context) {
            if (holders == null) {
                holders = new ArrayList<>();
            }
            if (holders.isEmpty() || holders.get(0).shared && context.shared) {
                holders.add(context);
                return CompletableFuture.completedFuture(null);
            }
            if (waiters == null) {
                waiters = new ArrayList<>();
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            waiters.add(new Waiter(context, future));
            return future;
        }

        public synchronized void unlock(Context context) {
            if (holders.remove(context)) {
                while (waiters != null && !waiters.isEmpty()
                        && (holders.isEmpty() || holders.get(0).shared && waiters.get(0).context.shared)) {
                    Waiter waiter = waiters.remove(0);
                    holders.add(waiter.context);
                    waiter.future.complete(null);
                }
            } else if (waiters != null) {
                for (Iterator<Waiter> it = waiters.iterator(); it.hasNext();) {
                    Waiter waiter = it.next();
                    if (waiter.context == context) {
                        it.remove();
                        waiter.future.cancel(false);
                    }
                }
            }
        }

    }

    static class Repo {

        final String repository;
        final Map<String, Lock> locks = new ConcurrentHashMap<>();

        public Repo(String repository) {
            this.repository = repository;
        }

        public CompletableFuture<?> lock(Context context, List<String> keys) {
            CompletableFuture<?>[] futures = keys.stream()
                    .map(k -> locks.computeIfAbsent(k, Lock::new))
                    .map(l -> l.lock(context))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        }

        public void unlock(Context context, List<String> keys) {
            keys.stream()
                    .map(k -> locks.computeIfAbsent(k, Lock::new))
                    .forEach(l -> l.unlock(context));
        }

    }

    static class Context {

        final Repo repo;
        final String id;
        final boolean shared;
        final List<String> locks = new CopyOnWriteArrayList<>();

        public Context(Repo repo, String contextId, boolean shared) {
            this.repo = repo;
            this.id = contextId;
            this.shared = shared;
        }

        public CompletableFuture<?> lock(List<String> keys) {
            locks.addAll(keys);
            return repo.lock(this, keys);
        }

        public void unlock() {
            repo.unlock(this, locks);
        }
    }

    private void use() {
        lastUsed = System.currentTimeMillis();
    }

    private void expirationCheck() {
        while (true) {
            long current = System.currentTimeMillis();
            if (current - lastUsed > 60 * 1000) {
                closing = true;
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket");
                    e.printStackTrace();
                }
                break;
            }
        }
    }

}
