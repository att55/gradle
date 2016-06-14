/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.nativeplatform.internal;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.gradle.api.CircularReferenceException;
import org.gradle.api.Nullable;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.api.internal.resolve.ProjectModelResolver;
import org.gradle.internal.graph.DirectedGraph;
import org.gradle.internal.graph.DirectedGraphRenderer;
import org.gradle.internal.graph.GraphNodeRenderer;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.language.base.plugins.ComponentModelBasePlugin;
import org.gradle.model.ModelMap;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.model.internal.type.ModelTypes;
import org.gradle.nativeplatform.NativeComponentSpec;
import org.gradle.nativeplatform.NativeLibraryBinary;
import org.gradle.platform.base.VariantComponentSpec;
import org.gradle.platform.base.internal.BinarySpecInternal;
import org.gradle.platform.base.internal.dependents.AbstractDependentBinariesResolutionStrategy;
import org.gradle.platform.base.internal.dependents.DefaultDependentBinariesResolvedResult;
import org.gradle.platform.base.internal.dependents.DependentBinariesResolvedResult;

import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;

public class NativeDependentBinariesResolutionStrategy extends AbstractDependentBinariesResolutionStrategy {

    private static class State {
        private final Map<String, NativeBinarySpecInternal> binaries = Maps.newLinkedHashMap();
        private final Map<String, List<NativeBinarySpecInternal>> dependencies = Maps.newLinkedHashMap();

        private final Map<String, List<NativeBinarySpecInternal>> dependents = Maps.newHashMap();

        List<NativeBinarySpecInternal> getDependents(NativeBinarySpecInternal target) {
            String key = keyOf(target);
            List<NativeBinarySpecInternal> result = dependents.get(key);
            if (result == null) {
                result = Lists.newArrayList();
                for (Map.Entry<String, List<NativeBinarySpecInternal>> entry : dependencies.entrySet()) {
                    if (entry.getValue().contains(target)) {
                        result.add(binaries.get(entry.getKey()));
                    }
                }
                dependents.put(key, result);
            }
            return result;
        }

        private String keyOf(BinarySpecInternal binary) {
            LibraryBinaryIdentifier id = binary.getId();
            return keyOf(id.getProjectPath(), id.getLibraryName(), id.getVariant());
        }

        private String keyOf(String project, String component, String binary) {
            String key = "";
            if (emptyToNull(project) == null) {
                key += Project.PATH_SEPARATOR;
            } else {
                key += project;
            }
            key += Project.PATH_SEPARATOR + component + Project.PATH_SEPARATOR + binary;
            return key;
        }
    }

    private final ProjectRegistry<ProjectInternal> projectRegistry;
    private final ProjectModelResolver projectModelResolver;

    public NativeDependentBinariesResolutionStrategy(ProjectRegistry<ProjectInternal> projectRegistry, ProjectModelResolver projectModelResolver) {
        super();
        checkNotNull(projectRegistry, "ProjectRegistry must not be null");
        checkNotNull(projectModelResolver, "ProjectModelResolver must not be null");
        this.projectRegistry = projectRegistry;
        this.projectModelResolver = projectModelResolver;
    }

    @Nullable
    @Override
    protected List<DependentBinariesResolvedResult> resolveDependents(BinarySpecInternal target) {
        if (!(target instanceof NativeBinarySpecInternal)) {
            return null;
        }
        return resolveDependentBinaries((NativeBinarySpecInternal) target);
    }

    private List<DependentBinariesResolvedResult> resolveDependentBinaries(NativeBinarySpecInternal target) {
        State state = buildState();
        Stack<NativeBinarySpecInternal> stack = new Stack<NativeBinarySpecInternal>();
        return buildResolvedResult(target, state, stack);
    }

    private State buildState() {
        State state = new State();

        List<ProjectInternal> orderedProjects = Ordering.usingToString().sortedCopy(projectRegistry.getAllProjects());
        for (ProjectInternal project : orderedProjects) {
            if (project.getPlugins().hasPlugin(ComponentModelBasePlugin.class)) {
                ModelRegistry modelRegistry = projectModelResolver.resolveProjectModel(project.getPath());
                ModelMap<NativeComponentSpec> components = modelRegistry.realize("components", ModelTypes.modelMap(NativeComponentSpec.class));
                for (NativeBinarySpecInternal binary : allBinariesOf(components.withType(VariantComponentSpec.class))) {
                    state.binaries.put(state.keyOf(binary), binary);
                }
            }
            for (NativeBinarySpecInternal extraBinary : getExtraBinaries(project, projectModelResolver)) {
                state.binaries.put(state.keyOf(extraBinary), extraBinary);
            }
        }

        for (Map.Entry<String, NativeBinarySpecInternal> entry : state.binaries.entrySet()) {
            String key = entry.getKey();
            NativeBinarySpecInternal nativeBinary = entry.getValue();
            if (state.dependencies.get(key) == null) {
                state.dependencies.put(key, Lists.<NativeBinarySpecInternal>newArrayList());
            }
            for (NativeLibraryBinary libraryBinary : nativeBinary.getDependentBinaries()) {
                // Skip prebuilt libraries
                if (libraryBinary instanceof NativeBinarySpecInternal) {
                    // Unfortunate cast! see LibraryBinaryLocator
                    state.dependencies.get(key).add((NativeBinarySpecInternal) libraryBinary);
                }
            }
            state.dependencies.get(key).addAll(getExtraDependents(nativeBinary));
        }

        return state;
    }

    protected List<NativeBinarySpecInternal> getExtraBinaries(Project project, ProjectModelResolver projectModelResolver) {
        return Collections.emptyList();
    }

    protected List<NativeBinarySpecInternal> getExtraDependents(NativeBinarySpecInternal nativeBinary) {
        return Collections.emptyList();
    }

    protected List<NativeBinarySpecInternal> allBinariesOf(ModelMap<VariantComponentSpec> components) {
        List<NativeBinarySpecInternal> binaries = Lists.newArrayList();
        for (VariantComponentSpec nativeComponent : components) {
            for (NativeBinarySpecInternal nativeBinary : nativeComponent.getBinaries().withType(NativeBinarySpecInternal.class)) {
                binaries.add(nativeBinary);
            }
        }
        return binaries;
    }

    private List<DependentBinariesResolvedResult> buildResolvedResult(NativeBinarySpecInternal target, State state, Stack<NativeBinarySpecInternal> stack) {
        if (stack.contains(target)) {
            onCircularDependencies(state, stack, target);
        }
        stack.push(target);
        List<DependentBinariesResolvedResult> result = Lists.newArrayList();
        List<NativeBinarySpecInternal> dependents = state.getDependents(target);
        for (NativeBinarySpecInternal dependent : dependents) {
            List<DependentBinariesResolvedResult> children = buildResolvedResult(dependent, state, stack);
            result.add(new DefaultDependentBinariesResolvedResult(dependent.getId(), dependent.isBuildable(), isTestSuite(dependent), children));
        }
        stack.pop();
        return result;
    }

    private void onCircularDependencies(final State state, final Stack<NativeBinarySpecInternal> stack, NativeBinarySpecInternal target) {
        GraphNodeRenderer<NativeBinarySpecInternal> nodeRenderer = new GraphNodeRenderer<NativeBinarySpecInternal>() {
            @Override
            public void renderTo(NativeBinarySpecInternal node, StyledTextOutput output) {
                output.withStyle(StyledTextOutput.Style.Identifier).text(node);
            }
        };
        DirectedGraph<NativeBinarySpecInternal, Object> directedGraph = new DirectedGraph<NativeBinarySpecInternal, Object>() {
            @Override
            public void getNodeValues(NativeBinarySpecInternal node, Collection<? super Object> values, Collection<? super NativeBinarySpecInternal> connectedNodes) {
                for (NativeBinarySpecInternal binary : stack) {
                    if (state.getDependents(node).contains(binary)) {
                        connectedNodes.add(binary);
                    }
                }
            }
        };
        DirectedGraphRenderer<NativeBinarySpecInternal> graphRenderer = new DirectedGraphRenderer<NativeBinarySpecInternal>(nodeRenderer, directedGraph);
        StringWriter writer = new StringWriter();
        graphRenderer.renderTo(target, writer);
        throw new CircularReferenceException(String.format("Circular dependency between the following binaries:%n%s", writer.toString()));
    }
}
