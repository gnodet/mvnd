/*
 * Copyright 2017 the original author or authors.
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
package org.mvndaemon.mvnd.builder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

/**
 * File origin:
 * https://github.com/takari/takari-smart-builder/blob/takari-smart-builder-0.6.1/src/main/java/io/takari/maven/builder/smart/DependencyGraph.java
 */
interface DependencyGraph<K> {

    static DependencyGraph<MavenProject> fromMaven(ProjectDependencyGraph graph, String rules) {
        List<MavenProject> projects = graph.getSortedProjects();
        Map<MavenProject, List<MavenProject>> upstreams = projects.stream()
                .collect(Collectors.toMap(p -> p, p -> graph.getUpstreamProjects(p, false)));
        Map<MavenProject, List<MavenProject>> downstreams = projects.stream()
                .collect(Collectors.toMap(p -> p, p -> graph.getDownstreamProjects(p, false)));

        if (rules != null) {
            for (String rule : rules.split("\\s*;\\s*|\n")) {
                if (rule.trim().isEmpty()) {
                    continue;
                }
                String[] parts = rule.split("\\s*->\\s*|\\s+before\\s+");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid rule: " + rule);
                }
                List<Set<MavenProject>> deps = Stream.of(parts).map(s -> Pattern.compile(
                        Arrays.stream(s.split("\\s*,\\s*|\\s+and\\s+"))
                                .map(String::trim)
                                .map(r -> r.contains(":") ? r : "*:" + r)
                                .map(r -> r.replaceAll("\\.", "\\.")
                                        .replaceAll("\\*", ".*"))
                                .collect(Collectors.joining("|"))))
                        .map(t -> projects.stream()
                                .filter(p -> t.matcher(p.getGroupId() + ":" + p.getArtifactId()).matches())
                                .collect(Collectors.toSet()))
                        .collect(Collectors.toList());

                Set<MavenProject> common = deps.get(0).stream().filter(deps.get(1)::contains).collect(Collectors.toSet());
                if (!common.isEmpty()) {
                    boolean leftWildcard = parts[0].contains("*");
                    boolean rightWildcard = parts[1].contains("*");
                    if (leftWildcard && rightWildcard) {
                        throw new IllegalArgumentException("Invalid rule: " + rule
                                + ".  Both left and right parts have wildcards and match the same project.");
                    } else if (leftWildcard) {
                        deps.get(0).removeAll(common);
                    } else if (rightWildcard) {
                        deps.get(1).removeAll(common);
                    } else {
                        throw new IllegalArgumentException(
                                "Invalid rule: " + rule + ". Both left and right parts match the same project.");
                    }
                }

                deps.get(1).forEach(p -> upstreams.get(p).addAll(deps.get(0)));
                deps.get(0).forEach(p -> downstreams.get(p).addAll(deps.get(1)));
            }
        }
        return new SimpleDependencyGraph<>(projects, upstreams, downstreams);
    }

    default Stream<K> getProjects() {
        return getProjectList().stream();
    }

    default boolean isRoot(K project) {
        return getUpstreamProjectList(project).isEmpty();
    }

    default Stream<K> getDownstreamProjects(K project) {
        return getDownstreamProjectList(project).stream();
    }

    default Stream<K> getUpstreamProjects(K project) {
        return getUpstreamProjectList(project).stream();
    }

    default int computeMaxWidth(int max, long maxTimeMillis) {
        return new DagWidth<>(this).getMaxWidth(max, maxTimeMillis);
    }

    default void store(Function<K, String> toString, Path path) {
        try (Writer w = Files.newBufferedWriter(path)) {
            getProjects().forEach(k -> {
                try {
                    w.write(toString.apply(k));
                    w.write(" = ");
                    w.write(getUpstreamProjects(k).map(toString).collect(Collectors.joining(",")));
                    w.write(System.lineSeparator());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    List<K> getProjectList();

    List<K> getDownstreamProjectList(K project);

    List<K> getUpstreamProjectList(K project);

}
