/*
 * Copyright 2020 the original author or authors.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DagWidthTest {

    @Test
    void testSimpleGraph() {
        //
        //       A    B
        //     / | \ / \
        //    C  D  E  F
        //        \/
        //         G
        Map<String, List<String>> upstreams = new HashMap<>();
        upstreams.put("A", Collections.emptyList());
        upstreams.put("B", Collections.emptyList());
        upstreams.put("C", Collections.singletonList("A"));
        upstreams.put("D", Collections.singletonList("A"));
        upstreams.put("E", Arrays.asList("A", "B"));
        upstreams.put("F", Collections.singletonList("B"));
        upstreams.put("G", Arrays.asList("D", "E"));
        DependencyGraph<String> graph = new SimpleGraph<>(upstreams);

        assertEquals(4, new DagWidth<>(graph).getMaxWidth(12));
    }

    @Test
    void testSingle() {
        //
        //       A
        //
        Map<String, List<String>> upstreams = new HashMap<>();
        upstreams.put("A", Collections.emptyList());
        DependencyGraph<String> graph = new SimpleGraph<>(upstreams);

        assertEquals(1, new DagWidth<>(graph).getMaxWidth(12));
    }

    @Test
    void testLinear() {
        //
        //       A -> B -> C -> D
        //
        Map<String, List<String>> upstreams = new HashMap<>();
        upstreams.put("A", Collections.emptyList());
        upstreams.put("B", Collections.singletonList("A"));
        upstreams.put("C", Collections.singletonList("B"));
        upstreams.put("D", Collections.singletonList("C"));
        DependencyGraph<String> graph = new SimpleGraph<>(upstreams);

        assertEquals(1, new DagWidth<>(graph).getMaxWidth(12));
    }

    @Test
    public void testHugeGraph() throws IOException {
        Map<String, List<String>> upstreams = new HashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getClass().getResourceAsStream("huge-graph.properties")))) {
            r.lines().forEach(l -> {
                int idxEq = l.indexOf(" = ");
                if (!l.startsWith("#") && idxEq > 0) {
                    String k = l.substring(0, idxEq).trim();
                    String[] ups = l.substring(idxEq + 3).trim().split(",");
                    List<String> list = Stream.of(ups).map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    upstreams.put(k, list);
                }
            });
        }
        DependencyGraph<String> graph = new SimpleGraph<>(upstreams);

        DagWidth<String> w = new DagWidth<>(graph);
        List<String> d = w.ensembleWithChildrenOf(graph.getDownstreamProjectList("org.apache.camel:camel"),
                "org.apache.camel:camel-parent");

        assertEquals(12, w.getMaxWidth(12));
    }

    static class SimpleGraph<K> implements DependencyGraph<K> {

        final List<K> nodes;
        final Map<K, List<K>> upstreams;
        final Map<K, List<K>> downstreams;

        public SimpleGraph(Map<K, List<K>> upstreams) {
            this.upstreams = upstreams;
            this.nodes = Stream.concat(upstreams.keySet().stream(), upstreams.values().stream().flatMap(List::stream))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            this.downstreams = this.nodes.stream().collect(Collectors.toMap(k -> k, k -> new ArrayList<>()));
            upstreams.forEach((k, ups) -> {
                ups.forEach(up -> downstreams.get(up).add(k));
            });
        }

        @Override
        public List<K> getProjectList() {
            return nodes;
        }

        @Override
        public List<K> getDownstreamProjectList(K project) {
            return downstreams.get(project);
        }

        @Override
        public List<K> getUpstreamProjectList(K project) {
            List<K> ups = upstreams.get(project);
            return Objects.requireNonNull(ups, () -> "Could not find upstreams for " + project);
        }
    }
}
