/*******************************************************************************
 * Copyright (c) 2011 Codehaus.org, SpringSource, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Andrew Eisenberg - Initial implemenation
 *******************************************************************************/
package org.codehaus.groovy.eclipse.dsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.codehaus.groovy.eclipse.GroovyLogManager;
import org.codehaus.groovy.eclipse.TraceCategory;
import org.codehaus.groovy.eclipse.dsl.contributions.IContributionElement;
import org.codehaus.groovy.eclipse.dsl.contributions.IContributionGroup;
import org.codehaus.groovy.eclipse.dsl.pointcuts.BindingSet;
import org.codehaus.groovy.eclipse.dsl.pointcuts.GroovyDSLDContext;
import org.codehaus.groovy.eclipse.dsl.pointcuts.IPointcut;
import org.eclipse.core.resources.IFile;

/**
 * Stores the pointcuts for a single project
 * @author andrew
 * @created Nov 17, 2010
 */
public class DSLDStore {

    private final Map<IPointcut, List<IContributionGroup>> pointcutContributionMap;  // maps pointcuts to their contributors
    private final Map<String, Set<IPointcut>> keyContextMap;  // maps unique keys (such as script names) to all the pointcuts that they produce
    public DSLDStore() {
        pointcutContributionMap = new HashMap<IPointcut, List<IContributionGroup>>();
        keyContextMap = new HashMap<String, Set<IPointcut>>();
    }
    
    public void addContributionGroup(IPointcut pointcut, IContributionGroup contribution) {
        List<IContributionGroup> contributions = pointcutContributionMap.get(pointcut);
        if (contributions == null) {
            contributions = new ArrayList<IContributionGroup>();
            pointcutContributionMap.put(pointcut, contributions);
        }
        contributions.add(contribution);
        
        String identifier = pointcut.getContainerIdentifier();
        Set<IPointcut> pointcuts = keyContextMap.get(identifier);
        if (pointcuts == null) {
            pointcuts = new HashSet<IPointcut>();
            keyContextMap.put(identifier, pointcuts);
        }
        pointcuts.add(pointcut);
    }
    
    
    public void purgeIdentifier(String identifier) {
        Set<IPointcut> pointcuts = keyContextMap.remove(identifier);
        if (pointcuts != null) {
            for (IPointcut pointcut : pointcuts) {
                pointcutContributionMap.remove(pointcut);
            }
        }
    }
    
    public void purgeAll() {
        keyContextMap.clear();
        pointcutContributionMap.clear();
    }

    /**
     * Creates a new {@link DSLDStore} based on the pattern passed in
     * only includes {@link IPointcut}s that match the pattern.
     * Sub-stores are meant to be short-lived and are not purged when a 
     * script changes.
     * 
     * @param patern the pattern to match against
     * @return a new {@link DSLDStore} containing only matches against the pattern
     */
    public DSLDStore createSubStore(GroovyDSLDContext pattern) {
        DSLDStore subStore = new DSLDStore();
        for (Entry<IPointcut, List<IContributionGroup>> entry : pointcutContributionMap.entrySet()) {
            if (entry.getKey().fastMatch(pattern)) {
                subStore.addAllContributions(entry.getKey(), entry.getValue());
            }
        }
        return subStore;
    }

    public void addAllContributions(IPointcut pointcut, List<IContributionGroup> contributions) {
        List<IContributionGroup> existing = pointcutContributionMap.get(pointcut);
        if (existing == null) {
            pointcutContributionMap.put(pointcut, contributions);
        } else {
            existing.addAll(contributions);
        }
    }
    public void addAllContexts(List<IPointcut> pointcuts, IContributionGroup contribution) {
        for (IPointcut pointcut : pointcuts) {
            addContributionGroup(pointcut, contribution);
        }
    }
    
    
    public void purgeFileFromStore(IFile file) {
        if (GroovyLogManager.manager.hasLoggers()) {
            GroovyLogManager.manager.log(TraceCategory.DSL, "Purging pointcut for DSL file " + file);
        }
        Set<IPointcut> pointcuts = keyContextMap.remove(convertToIdentifier(file));
        if (pointcuts != null) {
            for (IPointcut pointcut : pointcuts) {
                pointcutContributionMap.remove(pointcut);
            }
        }
    }

    /**
     * Find all contributions for this pattern and this declaring type
     * @param disabledScripts TODO
     * @return
     */
    public List<IContributionElement> findContributions(GroovyDSLDContext pattern, Set<String> disabledScripts) {
        List<IContributionElement> elts = new ArrayList<IContributionElement>();
        for (Entry<IPointcut, List<IContributionGroup>> entry : pointcutContributionMap.entrySet()) {
            IPointcut pointcut = entry.getKey();
            if (! disabledScripts.contains(pointcut.getContainerIdentifier())) {
                BindingSet matches = pointcut.matches(pattern);
                if (matches != null) {
                    for (IContributionGroup group : entry.getValue()) {
                        elts.addAll(group.getContributions(pattern, matches));
                    }
                }
            }
        }
        return elts;
    }
    
    public static String convertToIdentifier(IFile file) {
        return file.getFullPath().toPortableString();
    }
    
    public String[] getAllContextKeys() {
        return keyContextMap.keySet().toArray(new String[0]);
    }
} 