/*
 * Copyright 2003-2012 the original author or authors.
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

package org.codehaus.groovy.transform.stc;

import groovy.lang.*;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.classgen.asm.InvocationWriter;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.runtime.InvokerHelper;

import groovyjarjarasm.asm.Opcodes;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Base class for type checking extensions written in Groovy. Compared to its superclass, {@link TypeCheckingExtension},
 * this class adds a number of utility methods aimed at leveraging the syntax of the Groovy language to improve
 * expressivity and conciseness.
 *
 * @author Cedric Champeau
 * @since 2.1.0
 */
public class GroovyTypeCheckingExtensionSupport extends TypeCheckingExtension {

    // method name to DSL name
    private final static Map<String, String> METHOD_ALIASES;
    static {
        final Map<String, String> aliases = new HashMap<String, String>(20);
        aliases.put("onMethodSelection", "onMethodSelection");
        aliases.put("afterMethodCall", "afterMethodCall");
        aliases.put("beforeMethodCall", "beforeMethodCall");
        aliases.put("unresolvedVariable", "handleUnresolvedVariableExpression");
        aliases.put("unresolvedProperty", "handleUnresolvedProperty");
        aliases.put("unresolvedAttribute", "handleUnresolvedAttribute");
        aliases.put("ambiguousMethods", "handleAmbiguousMethods");
        aliases.put("methodNotFound", "handleMissingMethod");
        aliases.put("afterVisitMethod", "afterVisitMethod");
        aliases.put("beforeVisitMethod", "beforeVisitMethod");
        aliases.put("afterVisitClass", "afterVisitClass");
        aliases.put("beforeVisitClass", "beforeVisitClass");
        aliases.put("incompatibleAssignment", "handleIncompatibleAssignment");
        aliases.put("incompatibleReturnType", "handleIncompatibleReturnType");
        aliases.put("setup","setup");
        aliases.put("finish", "finish");

        METHOD_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private final Set<MethodNode> generatedMethods = new LinkedHashSet<MethodNode>();
    private final LinkedList<TypeCheckingScope> scopeData = new LinkedList<TypeCheckingScope>();

    // the following fields are closures executed in event-based methods
    private final Map<String, List<Closure>> eventHandlers = new HashMap<String, List<Closure>>();

    private final String scriptPath;
    private final TypeCheckingContext context;

    // this boolean is used through setHandled(boolean)
    private boolean handled = false;
    private final CompilationUnit compilationUnit;

    private boolean debug = false;
    private final static Logger LOG = Logger.getLogger(GroovyTypeCheckingExtensionSupport.class.getName());

    /**
     * Builds a type checking extension relying on a Groovy script (type checking DSL).
     *
     * @param typeCheckingVisitor the type checking visitor
     * @param scriptPath the path to the type checking script (in classpath)
     * @param compilationUnit
     */
    public GroovyTypeCheckingExtensionSupport(
            final StaticTypeCheckingVisitor typeCheckingVisitor,
            final String scriptPath, final CompilationUnit compilationUnit) {
        super(typeCheckingVisitor);
        this.scriptPath = scriptPath;
        this.context = typeCheckingVisitor.typeCheckingContext;
        this.compilationUnit = compilationUnit;
    }

    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    @Override
    public void setup() {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setScriptBaseClass("org.codehaus.groovy.transform.stc.GroovyTypeCheckingExtensionSupport.TypeCheckingDSL");
        ImportCustomizer ic = new ImportCustomizer();
        ic.addStarImports("org.codehaus.groovy.ast.expr");
        ic.addStaticStars("org.codehaus.groovy.ast.ClassHelper");
        ic.addStaticStars("org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport");
        config.addCompilationCustomizers(ic);
        final GroovyClassLoader transformLoader = compilationUnit!=null?compilationUnit.getTransformLoader():typeCheckingVisitor.getSourceUnit().getClassLoader();

        // since Groovy 2.2, it is possible to use FQCN for type checking extension scripts
        TypeCheckingDSL script = null;
        try {
            Class<?> clazz = transformLoader.loadClass(scriptPath, false, true);
            if (TypeCheckingDSL.class.isAssignableFrom(clazz)) {
                script = (TypeCheckingDSL) clazz.newInstance();
            }
        } catch (ClassNotFoundException e) {
            // silent
        } catch (InstantiationException e) {
            context.getErrorCollector().addFatalError(
                    new SimpleMessage("Static type checking extension '" + scriptPath + "' could not be loaded.",
                            config.getDebug(), typeCheckingVisitor.getSourceUnit()));
        } catch (IllegalAccessException e) {
            context.getErrorCollector().addFatalError(
                    new SimpleMessage("Static type checking extension '" + scriptPath + "' could not be loaded.",
                            config.getDebug(), typeCheckingVisitor.getSourceUnit()));
        }
        if (script==null) {
        ClassLoader cl = typeCheckingVisitor.getSourceUnit().getClassLoader();
        // cast to prevent incorrect @since 1.7 warning
        InputStream is = ((ClassLoader)transformLoader).getResourceAsStream(scriptPath);
        if (is == null) {
            // fallback to the source unit classloader
            is = cl.getResourceAsStream(scriptPath);
        }
        if (is == null) {
            // fallback to the compiler classloader
            cl = GroovyTypeCheckingExtensionSupport.class.getClassLoader();
            is = cl.getResourceAsStream(scriptPath);
        }
        // GRECLIPSE
        // don't place an error here if file not found during reconcile.
        // might be because build hasn't happened yet.
//        /*old{
        if (is == null) {
//        }new*/
//        if (is == null && !typeCheckingVisitor.getSourceUnit().isReconcile) {
            //GRECLIPSE end
            // if the input stream is still null, we've not found the extension
            context.getErrorCollector().addFatalError(
                    new SimpleMessage("Static type checking extension '" + scriptPath + "' was not found on the classpath.",
                            config.getDebug(), typeCheckingVisitor.getSourceUnit()));
        }
        try {
            GroovyShell shell = new GroovyShell(transformLoader, new Binding(), config);
                script = (TypeCheckingDSL) shell.parse(
                    new InputStreamReader(is, typeCheckingVisitor.getSourceUnit().getConfiguration().getSourceEncoding())
            );
            } catch (CompilationFailedException e) {
                throw new GroovyBugError("An unexpected error was thrown during custom type checking", e);
            } catch (UnsupportedEncodingException e) {
                throw new GroovyBugError("Unsupported encoding found in compiler configuration", e);
            }
        }
        if (script!=null) {
            script.extension = this;
            script.run();
            List<Closure> list = eventHandlers.get("setup");
            if (list != null) {
                for (Closure closure : list) {
                    safeCall(closure);
                }
            }
        }
    }

    @Override
    public void finish() {
        List<Closure> list = eventHandlers.get("finish");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure);
            }
        }
    }

    public void setHandled(final boolean handled) {
        this.handled = handled;
    }

    public TypeCheckingScope newScope() {
        TypeCheckingScope scope = new TypeCheckingScope(scopeData.peek());
        scopeData.addFirst(scope);
        return scope;
    }

    public TypeCheckingScope newScope(Closure code) {
        TypeCheckingScope scope = newScope();
        Closure callback = code.rehydrate(scope, this, this);
        callback.call();
        return scope;
    }

    public TypeCheckingScope scopeExit() {
        return scopeData.removeFirst();
    }

    public TypeCheckingScope getCurrentScope() {
        return scopeData.peek();
    }

    public TypeCheckingScope scopeExit(Closure code) {
        TypeCheckingScope scope = scopeData.peek();
        Closure copy = code.rehydrate(scope, this, this);
        copy.call();
        return scopeExit();
    }

    public boolean isGenerated(MethodNode node) {
        return generatedMethods.contains(node);
    }

    public List<MethodNode> unique(MethodNode node) {
        return Collections.singletonList(node);
    }

    public MethodNode newMethod(final String name, final Class returnType) {
        return newMethod(name, ClassHelper.make(returnType));
    }

    public MethodNode newMethod(final String name, final ClassNode returnType) {
        MethodNode node = new MethodNode(name,
                Opcodes.ACC_PUBLIC,
                returnType,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                EmptyStatement.INSTANCE);
        generatedMethods.add(node);
        return node;
    }

    public MethodNode newMethod(final String name,
                                final Callable<ClassNode> returnType) {
        MethodNode node = new MethodNode(name,
                Opcodes.ACC_PUBLIC,
                ClassHelper.OBJECT_TYPE,
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                EmptyStatement.INSTANCE) {
            @Override
            public ClassNode getReturnType() {
                try {
                    return returnType.call();
                } catch (Exception e) {
                    return super.getReturnType();
                }
            }
        };
        generatedMethods.add(node);
        return node;
    }

    public void delegatesTo(ClassNode type) {
        delegatesTo(type, Closure.OWNER_FIRST);
    }

    public void delegatesTo(ClassNode type, int strategy) {
        delegatesTo(type, strategy, typeCheckingVisitor.typeCheckingContext.delegationMetadata);
    }

    public void delegatesTo(ClassNode type, int strategy, DelegationMetadata parent) {
        typeCheckingVisitor.typeCheckingContext.delegationMetadata = new DelegationMetadata(type, strategy, parent);
    }

    public boolean isAnnotatedBy(ASTNode node, Class annotation) {
        return isAnnotatedBy(node, ClassHelper.make(annotation));
    }

    public boolean isAnnotatedBy(ASTNode node, ClassNode annotation) {
        return node instanceof AnnotatedNode && !((AnnotatedNode)node).getAnnotations(annotation).isEmpty();
    }

    public boolean isDynamic(VariableExpression var) {
        return var.getAccessedVariable() instanceof DynamicVariable;
    }

    public boolean isExtensionMethod(MethodNode node) {
        return node instanceof ExtensionMethodNode;
    }

    public ArgumentListExpression getArguments(MethodCall call) {
        return InvocationWriter.makeArgumentList(call.getArguments());
    }

    private Object safeCall(Closure closure, Object... args) {
        try {
            return closure.call(args);
        } catch (Exception err) {
            typeCheckingVisitor.getSourceUnit().addException(err);
            return null;
        }
    }

    @Override
    public void onMethodSelection(final Expression expression, final MethodNode target) {
        List<Closure> onMethodSelection = eventHandlers.get("onMethodSelection");
        if (onMethodSelection != null) {
            for (Closure closure : onMethodSelection) {
                safeCall(closure, expression, target);
            }
        }
    }

    @Override
    public void afterMethodCall(final MethodCall call) {
        List<Closure> onMethodSelection = eventHandlers.get("afterMethodCall");
        if (onMethodSelection != null) {
            for (Closure closure : onMethodSelection) {
                safeCall(closure, call);
            }
        }
    }

   @Override
    public boolean beforeMethodCall(final MethodCall call) {
       setHandled(false);
       List<Closure> onMethodSelection = eventHandlers.get("beforeMethodCall");
       if (onMethodSelection != null) {
           for (Closure closure : onMethodSelection) {
               safeCall(closure, call);
           }
       }
       return handled;
    }

    @Override
    public boolean handleUnresolvedVariableExpression(final VariableExpression vexp) {
        setHandled(false);
        List<Closure> onMethodSelection = eventHandlers.get("handleUnresolvedVariableExpression");
        if (onMethodSelection != null) {
            for (Closure closure : onMethodSelection) {
                safeCall(closure, vexp);
            }
        }
        return handled;
    }

    @Override
    public boolean handleUnresolvedProperty(final PropertyExpression pexp) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("handleUnresolvedProperty");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, pexp);
            }
        }
        return handled;
    }

    @Override
    public boolean handleUnresolvedAttribute(final AttributeExpression aexp) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("handleUnresolvedAttribute");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, aexp);
            }
        }
        return handled;
    }

    @Override
    public void afterVisitMethod(final MethodNode node) {
        List<Closure> list = eventHandlers.get("afterVisitMethod");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, node);
            }
        }
    }

    @Override
    public boolean beforeVisitClass(final ClassNode node) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("beforeVisitClass");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, node);
            }
        }
        return handled;
    }

    @Override
    public void afterVisitClass(final ClassNode node) {
        List<Closure> list = eventHandlers.get("afterVisitClass");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, node);
            }
        }
    }

    @Override
    public boolean beforeVisitMethod(final MethodNode node) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("beforeVisitMethod");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, node);
            }
        }
        return handled;
    }

    @Override
    public boolean handleIncompatibleAssignment(final ClassNode lhsType, final ClassNode rhsType, final Expression assignmentExpression) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("handleIncompatibleAssignment");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, lhsType, rhsType, assignmentExpression);
            }
        }
        return handled;
    }

    @Override
    public boolean handleIncompatibleReturnType(final ReturnStatement returnStatement, ClassNode inferredReturnType) {
        setHandled(false);
        List<Closure> list = eventHandlers.get("handleIncompatibleReturnType");
        if (list != null) {
            for (Closure closure : list) {
                safeCall(closure, returnStatement, inferredReturnType);
            }
        }
        return handled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MethodNode> handleMissingMethod(final ClassNode receiver, final String name, final ArgumentListExpression argumentList, final ClassNode[] argumentTypes, final MethodCall call) {
        List<Closure> onMethodSelection = eventHandlers.get("handleMissingMethod");
        List<MethodNode> methodList = new LinkedList<MethodNode>();
        if (onMethodSelection != null) {
            for (Closure closure : onMethodSelection) {
                Object result = safeCall(closure, receiver, name, argumentList, argumentTypes, call);
                if (result != null) {
                    if (result instanceof MethodNode) {
                        methodList.add((MethodNode) result);
                    } else if (result instanceof Collection) {
                        methodList.addAll((Collection<? extends MethodNode>) result);
                    } else {
                        throw new GroovyBugError("Type checking extension returned unexpected method list: " + result);
                    }
                }
            }
        }
        return methodList;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MethodNode> handleAmbiguousMethods(final List<MethodNode> nodes, final Expression origin) {
        List<Closure> onMethodSelection = eventHandlers.get("handleAmbiguousMethods");
        List<MethodNode> methodList = nodes;
        if (onMethodSelection != null) {
            Iterator<Closure> iterator = onMethodSelection.iterator();
            while (methodList.size()>1 && iterator.hasNext() ) {
                final Closure closure = iterator.next();
                Object result = safeCall(closure, methodList, origin);
                if (result != null) {
                    if (result instanceof MethodNode) {
                        methodList = Collections.singletonList((MethodNode) result);
                    } else if (result instanceof Collection) {
                        methodList = new LinkedList<MethodNode>((Collection<? extends MethodNode>) result);
                    } else {
                        throw new GroovyBugError("Type checking extension returned unexpected method list: " + result);
                    }
                }
            }
        }
        return methodList;
    }

    public boolean isMethodCall(Object o) {
        return o instanceof MethodCallExpression;
    }

    public boolean argTypesMatches(ClassNode[] argTypes, Class... classes) {
        if (classes == null) return argTypes == null || argTypes.length == 0;
        if (argTypes.length != classes.length) return false;
        boolean match = true;
        for (int i = 0; i < argTypes.length && match; i++) {
            match = matchWithOrWithourBoxing(argTypes[i], classes[i]);
        }
        return match;
    }

    private boolean matchWithOrWithourBoxing(final ClassNode argType, final Class aClass) {
        final boolean match;
        ClassNode type = ClassHelper.make(aClass);
        if (ClassHelper.isPrimitiveType(type) && !ClassHelper.isPrimitiveType(argType)) {
            type = ClassHelper.getWrapper(type);
        } else if (ClassHelper.isPrimitiveType(argType) && !ClassHelper.isPrimitiveType(type)) {
            type = ClassHelper.getUnwrapper(type);
        }
        match = argType.equals(type);
        return match;
    }

    public boolean argTypesMatches(MethodCall call, Class... classes) {
        ArgumentListExpression argumentListExpression = InvocationWriter.makeArgumentList(call.getArguments());
        ClassNode[] argumentTypes = typeCheckingVisitor.getArgumentTypes(argumentListExpression);
        return argTypesMatches(argumentTypes, classes);
    }

    public boolean firstArgTypesMatches(ClassNode[] argTypes, Class... classes) {
        if (classes == null) return argTypes == null || argTypes.length == 0;
        if (argTypes.length<classes.length) return false;
        boolean match = true;
        for (int i = 0; i < classes.length && match; i++) {
            match = matchWithOrWithourBoxing(argTypes[i], classes[i]);
        }
        return match;
    }

    public boolean firstArgTypesMatches(MethodCall call, Class... classes) {
        ArgumentListExpression argumentListExpression = InvocationWriter.makeArgumentList(call.getArguments());
        ClassNode[] argumentTypes = typeCheckingVisitor.getArgumentTypes(argumentListExpression);
        return firstArgTypesMatches(argumentTypes, classes);
    }

    public boolean argTypeMatches(ClassNode[] argTypes, int index, Class clazz) {
        if (index >= argTypes.length) return false;
        return matchWithOrWithourBoxing(argTypes[index], clazz);
    }

    public boolean argTypeMatches(MethodCall call, int index, Class clazz) {
        ArgumentListExpression argumentListExpression = InvocationWriter.makeArgumentList(call.getArguments());
        ClassNode[] argumentTypes = typeCheckingVisitor.getArgumentTypes(argumentListExpression);
        return argTypeMatches(argumentTypes, index, clazz);
    }

    @SuppressWarnings("unchecked")
    public <R> R withTypeChecker(Closure<R> code) {
        Closure<R> clone = (Closure<R>) code.clone();
        clone.setDelegate(typeCheckingVisitor);
        clone.setResolveStrategy(Closure.DELEGATE_FIRST);
        return clone.call();
    }

    /**
     * Used to instruct the type checker that the call is a dynamic method call.
     * Calling this method automatically sets the handled flag to true. The expected
     * return type of the dynamic method call is Object.
     * @param call the method call which is a dynamic method call
     * @return a virtual method node with the same name as the expected call
     */
    public MethodNode makeDynamic(MethodCall call) {
        return makeDynamic(call, ClassHelper.OBJECT_TYPE);
    }

    /**
     * Used to instruct the type checker that the call is a dynamic method call.
     * Calling this method automatically sets the handled flag to true.
     * @param call the method call which is a dynamic method call
     * @param returnType the expected return type of the dynamic call
     * @return a virtual method node with the same name as the expected call
     */
    public MethodNode makeDynamic(MethodCall call, ClassNode returnType) {
        TypeCheckingContext.EnclosingClosure enclosingClosure = context.getEnclosingClosure();
        MethodNode enclosingMethod = context.getEnclosingMethod();
        ((ASTNode)call).putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, returnType);
        if (enclosingClosure!=null) {
            enclosingClosure.getClosureExpression().putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, Boolean.TRUE);
        } else {
            enclosingMethod.putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, Boolean.TRUE);
        }
        setHandled(true);
        if (debug) {
            LOG.info("Turning "+call.getText()+" into a dynamic method call returning "+returnType.toString(false));
        }
        return new MethodNode(call.getMethodAsString(), 0, returnType, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
    }

    /**
     * Instructs the type checker that a property access is dynamic, returning an instance of an Object.
     * Calling this method automatically sets the handled flag to true.
     * @param pexp the property or attribute expression
     */
    public void makeDynamic(PropertyExpression pexp) {
        makeDynamic(pexp, ClassHelper.OBJECT_TYPE);
    }

    /**
     * Instructs the type checker that a property access is dynamic.
     * Calling this method automatically sets the handled flag to true.
     * @param pexp the property or attribute expression
     * @param returnType the type of the property
     */
    public void makeDynamic(PropertyExpression pexp, ClassNode returnType) {
        context.getEnclosingMethod().putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, Boolean.TRUE);
        pexp.putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, returnType);
        storeType(pexp, returnType);
        setHandled(true);
        if (debug) {
            LOG.info("Turning '"+pexp.getText()+"' into a dynamic property access of type "+returnType.toString(false));
    	}
    }

    /**
     * Instructs the type checker that an unresolved variable is a dynamic variable of type Object.
     * Calling this method automatically sets the handled flag to true.
     * @param vexp the dynamic variable
     */
    public void makeDynamic(VariableExpression vexp) {
        makeDynamic(vexp, ClassHelper.OBJECT_TYPE);
    }

    /**
     * Instructs the type checker that an unresolved variable is a dynamic variable.
     * @param returnType the type of the dynamic variable
     * Calling this method automatically sets the handled flag to true.
     * @param vexp the dynamic variable
     */
    public void makeDynamic(VariableExpression vexp, ClassNode returnType) {
        context.getEnclosingMethod().putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, Boolean.TRUE);
        vexp.putNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION, returnType);
        storeType(vexp, returnType);
        setHandled(true);
        if (debug) {
            LOG.info("Turning '"+vexp.getText()+"' into a dynamic variable access of type "+returnType.toString(false));
        }
    }

    public void log(String message) {
        LOG.info(message);
    }

    // -------------------------------------
    // delegate to the type checking context
    // -------------------------------------

    public BinaryExpression getEnclosingBinaryExpression() {
        return context.getEnclosingBinaryExpression();
    }

    public void pushEnclosingBinaryExpression(final BinaryExpression binaryExpression) {
        context.pushEnclosingBinaryExpression(binaryExpression);
    }

    public void pushEnclosingClosureExpression(final ClosureExpression closureExpression) {
        context.pushEnclosingClosureExpression(closureExpression);
    }

    public Expression getEnclosingMethodCall() {
        return context.getEnclosingMethodCall();
    }

    public Expression popEnclosingMethodCall() {
        return context.popEnclosingMethodCall();
    }

    public MethodNode popEnclosingMethod() {
        return context.popEnclosingMethod();
    }

    public ClassNode getEnclosingClassNode() {
        return context.getEnclosingClassNode();
    }

    public List<MethodNode> getEnclosingMethods() {
        return context.getEnclosingMethods();
    }

    public MethodNode getEnclosingMethod() {
        return context.getEnclosingMethod();
    }

    public void popTemporaryTypeInfo() {
        context.popTemporaryTypeInfo();
    }

    public void pushEnclosingClassNode(final ClassNode classNode) {
        context.pushEnclosingClassNode(classNode);
    }

    public BinaryExpression popEnclosingBinaryExpression() {
        return context.popEnclosingBinaryExpression();
    }

    public List<ClassNode> getEnclosingClassNodes() {
        return context.getEnclosingClassNodes();
    }

    public List<TypeCheckingContext.EnclosingClosure> getEnclosingClosureStack() {
        return context.getEnclosingClosureStack();
    }

    public ClassNode popEnclosingClassNode() {
        return context.popEnclosingClassNode();
    }

    public void pushEnclosingMethod(final MethodNode methodNode) {
        context.pushEnclosingMethod(methodNode);
    }

    public List<BinaryExpression> getEnclosingBinaryExpressionStack() {
        return context.getEnclosingBinaryExpressionStack();
    }

    public TypeCheckingContext.EnclosingClosure getEnclosingClosure() {
        return context.getEnclosingClosure();
    }

    public List<Expression> getEnclosingMethodCalls() {
        return context.getEnclosingMethodCalls();
    }

    public void pushEnclosingMethodCall(final Expression call) {
        context.pushEnclosingMethodCall(call);
    }

    public TypeCheckingContext.EnclosingClosure popEnclosingClosure() {
        return context.popEnclosingClosure();
    }

    public void pushTemporaryTypeInfo() {
        context.pushTemporaryTypeInfo();
    }

    // --------------------------------------------
    // end of delegate to the type checking context
    // --------------------------------------------

    @SuppressWarnings("serial")
    private class TypeCheckingScope extends LinkedHashMap<String, Object> {
        private final TypeCheckingScope parent;

        private TypeCheckingScope(final TypeCheckingScope parentScope) {
            this.parent = parentScope;
        }

        @SuppressWarnings("unused")
        public TypeCheckingScope getParent() {
            return parent;
        }

    }

    public abstract static class TypeCheckingDSL extends Script {
        private GroovyTypeCheckingExtensionSupport extension;

        @Override
        public Object getProperty(final String property) {
            try {
                return InvokerHelper.getProperty(extension, property);
            } catch (Exception e) {
                return super.getProperty(property);
            }
        }

        @Override
        public void setProperty(final String property, final Object newValue) {
            try {
                InvokerHelper.setProperty(extension, property, newValue);
            } catch (Exception e) {
                super.setProperty(property, newValue);
            }
        }

        @Override
        public Object invokeMethod(final String name, final Object args) {
            if (name.startsWith("is") && name.endsWith("Expression") && args instanceof Object[] && ((Object[]) args).length == 1) {
                String type = name.substring(2);
                Object target = ((Object[]) args)[0];
                if (target==null) return false;
                try {
                    Class typeClass = Class.forName("org.codehaus.groovy.ast.expr."+type);
                    return typeClass.isAssignableFrom(target.getClass());
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
            if (args instanceof Object[] && ((Object[]) args).length == 1 && ((Object[]) args)[0] instanceof Closure) {
                Object[] argsArray = (Object[]) args;
                String methodName = METHOD_ALIASES.get(name);
                if (methodName == null) {
                    return InvokerHelper.invokeMethod(extension, name, args);
                }
                List<Closure> closures = extension.eventHandlers.get(methodName);
                if (closures == null) {
                    closures = new LinkedList<Closure>();
                    extension.eventHandlers.put(methodName, closures);
                }
                closures.add((Closure) argsArray[0]);
                return null;
            } else {
                return InvokerHelper.invokeMethod(extension, name, args);
            }
        }
    }
}
