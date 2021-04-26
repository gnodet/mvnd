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
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.Priority;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.SyncContextFactory;
import org.eclipse.aether.metadata.Metadata;

@Named
@Priority(Integer.MAX_VALUE)
@Singleton
public class MvndSyncContextFactory implements SyncContextFactory {

    public static final String REQUEST_CONTEXT = "request-context";
    public static final String REQUEST_ACQUIRE = "request-acquire";
    public static final String REQUEST_CLOSE = "request-close";
    public static final String RESPONSE_CONTEXT = "response-context";
    public static final String RESPONSE_ACQUIRE = "response-acquire";
    public static final String RESPONSE_CLOSE = "response-close";

    Socket socket;
    DataOutputStream output;
    DataInputStream input;
    Thread receiver;
    AtomicInteger requestId = new AtomicInteger();
    Map<Integer, CompletableFuture<List<String>>> responses = new ConcurrentHashMap<>();

    @Override
    public SyncContext newInstance(RepositorySystemSession session, boolean shared) {
        return new MvndSyncContext(session, shared);
    }

    synchronized Socket ensureInitialized() throws IOException {
        if (socket == null) {
            socket = createClient();
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            receiver = new Thread(this::receive);
            receiver.setDaemon(true);
            receiver.start();
        }
        return socket;
    }

    synchronized Socket createClient() throws IOException {
        Path registryFile = Paths.get(System.getProperty("user.home"))
                .resolve(".m2/mvnd/sync.bin").toAbsolutePath().normalize();
        if (!Files.isRegularFile(registryFile)) {
            if (!Files.isDirectory(registryFile.getParent())) {
                Files.createDirectories(registryFile.getParent());
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(registryFile.toFile(), "rw")) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            try (FileLock lock = raf.getChannel().lock()) {
                long pid = 0;
                int port = 0;
                try {
                    pid = raf.readLong();
                    port = raf.readInt();
                } catch (EOFException e) {
                    // ignore
                }
                if (port > 0 && pid > 0) {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(loopback, port));
                        return socket;
                    } catch (IOException e) {
                        // ignore
                    }
                    try {
                        ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly);
                    } catch (Throwable t) {
                        // ignore
                    }
                }
                ServerSocket ss = new ServerSocket();
                ss.bind(new InetSocketAddress(loopback, 0));
                int tmpport = ss.getLocalPort();
                long rand = new Random().nextInt();

                List<String> args = new ArrayList<>();
                String javaHome = System.getenv("JAVA_HOME");
                if (javaHome == null) {
                    javaHome = System.getProperty("java.home");
                }
                boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                String java = Paths.get(javaHome).resolve(win ? "bin\\java.exe" : "bin/java").toAbsolutePath().toString();
                args.add(java);
                String classpath;
                String className = getClass().getName().replace('.', '/') + ".class";
                String url = getClass().getClassLoader().getResource(className).toString();
                if (url.startsWith("jar:")) {
                    classpath = url.substring("jar:".length(), url.indexOf("!/"));
                } else if (url.startsWith("file:")) {
                    classpath = url.substring("file:".length(), url.indexOf(className));
                } else {
                    throw new IllegalStateException();
                }
                args.add("-cp");
                args.add(classpath);
                args.add(SyncServer.class.getName());
                args.add(Integer.toString(tmpport));
                args.add(Long.toString(rand));
                ProcessBuilder processBuilder = new ProcessBuilder();
                ProcessBuilder.Redirect discard = ProcessBuilder.Redirect.to(new File(win ? "NUL" : "/dev/null"));
                discard = ProcessBuilder.Redirect.INHERIT;
                Process process = processBuilder
                        .directory(registryFile.getParent().toFile())
                        .command(args)
                        .redirectOutput(discard)
                        .redirectError(discard)
                        .start();

                Future<long[]> future = ForkJoinPool.commonPool().submit(() -> {
                    Socket s = ss.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    long rand2 = dis.readLong();
                    long pid2 = dis.readLong();
                    long port2 = dis.readInt();
                    return new long[] { rand2, pid2, port2 };
                });
                long[] res = future.get(5, TimeUnit.SECONDS);
                if (rand != res[0]) {
                    process.destroyForcibly();
                }
                ss.close();

                pid = res[1];
                port = (int) res[2];
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(loopback, port));

                raf.seek(0);
                raf.writeLong(pid);
                raf.writeInt(port);
                return socket;
            } catch (Exception e) {
                throw new RuntimeException("Unable to create and connect to lock server", e);
            }
        }
    }

    void receive() {
        try {
            while (true) {
                int id = input.readInt();
                int sz = input.readInt();
                List<String> s = new ArrayList<>(sz);
                for (int i = 0; i < sz; i++) {
                    s.add(input.readUTF());
                }
                CompletableFuture<List<String>> f = responses.remove(id);
                if (f == null || s.isEmpty()) {
                    close();
                    throw new IllegalStateException("Protocol error");
                }
                f.complete(s);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    List<String> send(List<String> request) throws IOException {
        ensureInitialized();
        int id = requestId.incrementAndGet();
        CompletableFuture<List<String>> response = new CompletableFuture<>();
        responses.put(id, response);
        synchronized (output) {
            output.writeInt(id);
            output.writeInt(request.size());
            for (String s : request) {
                output.writeUTF(s);
            }
            output.flush();
        }
        try {
            return response.get();
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("Interrupted").initCause(e);
        } catch (ExecutionException e) {
            throw new IOException("Execution error", e);
        }
    }

    synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException t) {
                // ignore
            }
            socket = null;
            input = null;
            output = null;
        }
        if (receiver != null) {
            receiver.interrupt();
            try {
                receiver.join(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        Throwable t = new IOException("Closing");
        responses.values().forEach(f -> f.completeExceptionally(t));
        responses.clear();
    }

    String newContext(String repo, boolean shared) {
        try {
            List<String> response = send(Arrays.asList(REQUEST_CONTEXT, repo, Boolean.toString(shared)));
            if (response.size() != 2 || !RESPONSE_CONTEXT.equals(response.get(0))) {
                throw new IOException("Unexpected response: " + response);
            }
            return response.get(1);
        } catch (Exception e) {
            close();
            throw new RuntimeException("Unable to create new context", e);
        }
    }

    void lock(String contextId, Collection<String> keys) {
        try {
            List<String> req = new ArrayList<>(keys.size() + 2);
            req.add(REQUEST_ACQUIRE);
            req.add(contextId);
            req.addAll(keys);
            List<String> response = send(req);
            if (response.size() != 1 || !RESPONSE_ACQUIRE.equals(response.get(0))) {
                throw new IOException("Unexpected response: " + response);
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException("Unable to perform lock", e);
        }
    }

    void unlock(String contextId) {
        try {
            List<String> response = send(Arrays.asList(REQUEST_CLOSE, contextId));
            if (response.size() != 1 || !RESPONSE_CLOSE.equals(response.get(0))) {
                throw new IOException("Unexpected response: " + response);
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException("Unable to perform lock", e);
        }
    }

    class MvndSyncContext implements SyncContext {

        RepositorySystemSession session;
        boolean shared;
        String contextId;

        public MvndSyncContext(RepositorySystemSession session, boolean shared) {
            this.session = session;
            this.shared = shared;
            this.contextId = newContext(session.getLocalRepository().getBasedir().toString(), shared);
        }

        @Override
        public void acquire(Collection<? extends Artifact> artifacts, Collection<? extends Metadata> metadatas) {
            Collection<String> keys = new TreeSet<>();
            stream(artifacts).map(this::getKey).forEach(keys::add);
            stream(metadatas).map(this::getKey).forEach(keys::add);
            if (keys.isEmpty()) {
                return;
            }
            lock(contextId, keys);
        }

        @Override
        public void close() {
            if (contextId != null) {
                unlock(contextId);
            }
        }

        @Override
        public String toString() {
            return "MvndSyncContext{" +
                    "session=" + session +
                    ", shared=" + shared +
                    ", contextId='" + contextId + '\'' +
                    '}';
        }

        private <T> Stream<T> stream(Collection<T> col) {
            return col != null ? col.stream() : Stream.empty();
        }

        private String getKey(Artifact a) {
            return "artifact:" + a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getBaseVersion();
        }

        private String getKey(Metadata m) {
            StringBuilder key = new StringBuilder("metadata:");
            if (!m.getGroupId().isEmpty()) {
                key.append(m.getGroupId());
                if (!m.getArtifactId().isEmpty()) {
                    key.append(':').append(m.getArtifactId());
                    if (!m.getVersion().isEmpty()) {
                        key.append(':').append(m.getVersion());
                    }
                }
            }
            return key.toString();
        }

    }

}
