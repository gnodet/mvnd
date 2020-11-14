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

import java.util.List;
import java.util.Map;

public class SimpleDependencyGraph<K> implements DependencyGraph<K> {

    final List<K> projects;
    final Map<K, List<K>> upstreams;
    final Map<K, List<K>> downstreams;

    public SimpleDependencyGraph(List<K> projects, Map<K, List<K>> upstreams, Map<K, List<K>> downstreams) {
        this.projects = projects;
        this.upstreams = upstreams;
        this.downstreams = downstreams;
    }

    @Override
    public List<K> getProjectList() {
        return projects;
    }

    @Override
    public List<K> getDownstreamProjectList(K project) {
        return downstreams.get(project);
    }

    @Override
    public List<K> getUpstreamProjectList(K project) {
        return upstreams.get(project);
    }

}
