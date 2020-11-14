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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DagWidth<K> {

    private final DependencyGraph<K> graph;
    private final Map<K, Set<K>> allUpstreams = new HashMap<>();

    public DagWidth(DependencyGraph<K> graph) {
        this.graph = graph;
        graph.getProjects().forEach(this::allUpstreams);
    }

    public int getMaxWidth() {
        return getMaxWidth(Integer.MAX_VALUE);
    }

    public int getMaxWidth(int maxmax) {
        return getMaxWidth(maxmax, Long.MAX_VALUE);
    }

    public int getMaxWidth(int maxmax, long maxTimeMillis) {
        int max = 0;
        if (maxmax < allUpstreams.size()) {
            // try inverted upstream bound
            Map<Set<K>, Set<K>> mapByUpstreams = new HashMap<>();
            allUpstreams.forEach((k, ups) -> {
                mapByUpstreams.computeIfAbsent(ups, n -> new HashSet<>()).add(k);
            });
            max = mapByUpstreams.values().stream()
                    .mapToInt(Set::size)
                    .max()
                    .orElse(0);
            if (max >= maxmax) {
                return maxmax;
            }
        }
        long tmax = System.currentTimeMillis() + maxTimeMillis;
        int tries = 0;
        SubsetIterator iterator = new SubsetIterator(getRoots());
        while (max < maxmax && iterator.hasNext()) {
            if (++tries % 100 == 0 && System.currentTimeMillis() < tmax) {
                return maxmax;
            }
            List<K> l = iterator.next();
            max = Math.max(max, l.size());
        }
        return max;
        //        try {
        //            return new ForkJoinPool().submit(() -> childEnsembles(getRoots())
        //                    .mapToInt(List::size)
        //                    .max().orElse(0)).get();
        //        } catch (Exception e) {
        //            return 0;
        //        }
        //        return childEnsembles(getRoots())
        //                .mapToInt(List::size)
        //                .max()
        //                .orElse(0);
    }

    private class SubsetIterator implements Iterator<List<K>> {

        final List<List<K>> nexts = new ArrayList<>();
        final Set<List<K>> visited = new HashSet<>();

        public SubsetIterator(List<K> roots) {
            nexts.add(roots);
        }

        @Override
        public boolean hasNext() {
            return !nexts.isEmpty();
        }

        @Override
        public List<K> next() {
            List<K> list = nexts.remove(0);
            list.stream()
                    .map(node -> ensembleWithChildrenOf(list, node))
                    .filter(visited::add)
                    .forEach(nexts::add);
            return list;
        }
    }

    private List<K> getRoots() {
        return graph.getProjects()
                .filter(graph::isRoot)
                .collect(Collectors.toList());
    }

    /**
     * Get a stream of all subset of descendants of the given nodes
     */
    private Stream<List<K>> childEnsembles(List<K> list) {
        return Stream.concat(
                Stream.of(list),
                list.parallelStream()
                        .map(node -> ensembleWithChildrenOf(list, node))
                        .flatMap(this::childEnsembles));
    }

    List<K> ensembleWithChildrenOf(List<K> list, K node) {
        return Stream.concat(
                list.stream().filter(k -> !Objects.equals(k, node)),
                graph.getDownstreamProjects(node)
                        .filter(k -> allUpstreams(k).stream().noneMatch(k2 -> !Objects.equals(k2, node) && list.contains(k2))))
                .distinct().collect(Collectors.toList());
    }

    private Set<K> allUpstreams(K node) {
        Set<K> aups = allUpstreams.get(node);
        if (aups == null) {
            aups = Stream.concat(
                    graph.getUpstreamProjects(node),
                    graph.getUpstreamProjects(node).map(this::allUpstreams).flatMap(Set::stream))
                    .collect(Collectors.toSet());
            allUpstreams.put(node, aups);
        }
        return aups;
    }

}
