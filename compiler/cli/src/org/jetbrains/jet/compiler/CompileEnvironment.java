/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.compiler;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.LocalTimeCounter;
import jet.modules.Module;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.codegen.ClassFileFactory;
import org.jetbrains.jet.codegen.GeneratedClassLoader;
import org.jetbrains.jet.codegen.GenerationState;
import org.jetbrains.jet.compiler.messages.MessageCollector;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.psi.JetPsiUtil;
import org.jetbrains.jet.lang.resolve.FqName;
import org.jetbrains.jet.lang.resolve.java.CompilerDependencies;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.plugin.JetLanguage;
import org.jetbrains.jet.plugin.JetMainDetector;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.List;

/**
 * The environment for compiling a bunch of source files or
 *
 * @author yole
 */
public class CompileEnvironment {
    private final Disposable rootDisposable;
    private JetCoreEnvironment environment;

    private final MessageCollector messageCollector;

    @NotNull
    private final CompilerDependencies compilerDependencies;

    /**
     * NOTE: It's very important to call dispose for every object of this class or there will be memory leaks.
     * @see Disposer
     */
    public CompileEnvironment(@NotNull MessageCollector messageCollector, @NotNull CompilerDependencies compilerDependencies) {
        this.messageCollector = messageCollector;
        this.compilerDependencies = compilerDependencies;
        this.rootDisposable = new Disposable() {
            @Override
            public void dispose() {
            }
        };
        this.environment = new JetCoreEnvironment(rootDisposable, compilerDependencies);
    }


    public void dispose() {
        Disposer.dispose(rootDisposable);
    }

    public boolean compileModuleScript(String moduleScriptFile, @Nullable String jarPath, @Nullable String outputDir, boolean jarRuntime) {
        List<Module> modules = CompileEnvironmentUtil.loadModuleScript(moduleScriptFile, messageCollector);

        if (modules == null) {
            throw new CompileEnvironmentException("Module script " + moduleScriptFile + " compilation failed");
        }

        if (modules.isEmpty()) {
            throw new CompileEnvironmentException("No modules where defined by " + moduleScriptFile);
        }

        final String directory = new File(moduleScriptFile).getParent();
        for (Module moduleBuilder : modules) {
            if (compilerDependencies.getRuntimeJar() != null) {
                addToClasspath(compilerDependencies.getRuntimeJar());
            }
            ClassFileFactory moduleFactory = compileModule(moduleBuilder, directory);
            if (moduleFactory == null) {
                return false;
            }
            if (outputDir != null) {
                CompileEnvironmentUtil.writeToOutputDirectory(moduleFactory, outputDir);
            }
            else {
                String path = jarPath != null ? jarPath : new File(directory, moduleBuilder.getModuleName() + ".jar").getPath();
                try {
                    CompileEnvironmentUtil.writeToJar(moduleFactory, new FileOutputStream(path), null, jarRuntime);
                }
                catch (FileNotFoundException e) {
                    throw new CompileEnvironmentException("Invalid jar path " + path, e);
                }
            }
        }
        return true;
    }

    public ClassFileFactory compileModule(Module moduleBuilder, String directory) {
        if (moduleBuilder.getSourceFiles().isEmpty()) {
            throw new CompileEnvironmentException("No source files where defined");
        }

        for (String sourceFile : moduleBuilder.getSourceFiles()) {
            File source = new File(sourceFile);
            if (!source.isAbsolute()) {
                source = new File(directory, sourceFile);
            }

            if (!source.exists()) {
                throw new CompileEnvironmentException("'" + source + "' does not exist");
            }

            environment.addSources(source.getPath());
        }
        for (String classpathRoot : moduleBuilder.getClasspathRoots()) {
            environment.addToClasspath(new File(classpathRoot));
        }

        CompileEnvironmentUtil.ensureRuntime(environment, compilerDependencies);

        return analyze();
    }

    public ClassLoader compileText(String code) {
        environment.addSources(new LightVirtualFile("script" + LocalTimeCounter.currentTime() + ".kt", JetLanguage.INSTANCE, code));

        ClassFileFactory factory = analyze();
        if (factory == null) {
            return null;
        }
        return new GeneratedClassLoader(factory);
    }

    public boolean compileBunchOfSources(String sourceFileOrDir, String jar, String outputDir, boolean includeRuntime) {
        environment.addSources(sourceFileOrDir);

        return compileBunchOfSources(jar, outputDir, includeRuntime);
    }

    public boolean compileBunchOfSourceDirectories(List<String> sources, String jar, String outputDir, boolean includeRuntime) {
        for (String source : sources) {
            environment.addSources(source);
        }

        return compileBunchOfSources(jar, outputDir, includeRuntime);
    }

    private boolean compileBunchOfSources(String jar, String outputDir, boolean includeRuntime) {
        FqName mainClass = null;
        for (JetFile file : environment.getSourceFiles()) {
            if (JetMainDetector.hasMain(file.getDeclarations())) {
                FqName fqName = JetPsiUtil.getFQName(file);
                mainClass = fqName.child(JvmAbi.PACKAGE_CLASS);
                break;
            }
        }

        CompileEnvironmentUtil.ensureRuntime(environment, compilerDependencies);

        ClassFileFactory factory = analyze();
        if (factory == null) {
            return false;
        }

        if (jar != null) {
            try {
                CompileEnvironmentUtil.writeToJar(factory, new FileOutputStream(jar), mainClass, includeRuntime);
            } catch (FileNotFoundException e) {
                throw new CompileEnvironmentException("Invalid jar path " + jar, e);
            }
        }
        else if (outputDir != null) {
            CompileEnvironmentUtil.writeToOutputDirectory(factory, outputDir);
        }
        else {
            throw new CompileEnvironmentException("Output directory or jar file is not specified - no files will be saved to the disk");
        }
        return true;
    }

    private ClassFileFactory analyze() {
        boolean stubs = compilerDependencies.getCompilerSpecialMode().isStubs();
        GenerationState generationState =
                KotlinToJVMBytecodeCompiler
                        .analyzeAndGenerate(environment, compilerDependencies, messageCollector, stubs);
        if (generationState == null) {
            return null;
        }
        return generationState.getFactory();
    }

    /**
     * Add path specified to the compilation environment.
     * @param paths paths to add
     */
    public void addToClasspath(File ... paths) {
        for (File path : paths) {
            if (!path.exists()) {
                throw new CompileEnvironmentException("'" + path + "' does not exist");
            }
            environment.addToClasspath(path);
        }
    }

    /**
     * Add path specified to the compilation environment.
     * @param paths paths to add
     */
    public void addToClasspath(String ... paths) {
        for (String path : paths) {
            addToClasspath( new File(path));
        }
    }

    public void setStdlib(String stdlib) {
        File file = new File(stdlib);
        addToClasspath(file);
    }

    public JetCoreEnvironment getEnvironment() {
        return environment;
    }

    @NotNull
    public CompilerDependencies getCompilerDependencies() {
        return compilerDependencies;
    }
}
