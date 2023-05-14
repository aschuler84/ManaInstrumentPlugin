package at.mana.instrument;

import at.mana.core.util.ConsoleColors;
import at.mana.exec.rapl.AlteredByMana;
import at.mana.exec.rapl.TraceMana;
import at.mana.exec.rapl.internal.RaplInternalCommand;
import at.mana.exec.rapl.internal.RaplInternalCommandParameter;
import at.mana.exec.rapl.internal.RaplJsonDecorator;
import at.mana.exec.rapl.internal.RaplSocketDecorator;
import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;


@Mojo(  name = "instrument-methods",
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        requiresDependencyResolution = ResolutionScope.TEST)
public class InstrumentArtifactMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;


    /**
     * Logger for logging to mvn commandline
     */
    private Log log;

    /**
     * Comma separated List of output directories for modifying classes
     */
    @Parameter(defaultValue = "${project.build.outputDirectory},${project.build.testOutputDirectory}", property = "outputDir", required = true)
    private String outputDirectories;

    /**
     * Comma separated list of all java packages that contain classes to be instrumented
     */
    @Parameter(property = "packages", required = true)
    protected String packageList;

    @Parameter(property = "outputFolder", required = false)
    private String outputFolder;

    @Parameter(property = "samples", required = false, defaultValue = "50")
    protected int samplingRate;

    @Parameter(property = "noOfSamples", required = false)
    private int noOfSamples;

    @Parameter(property = "port", required = false)
    private int port = -1;

    @Parameter(property = "rapl", required = false)
    private String raplHome;

    @Parameter(property = "trace", required = false)
    private boolean trace = false;

    private final ClassPool classPool = new ClassPool(ClassPool.getDefault());

    private final String RAPL_HOME = System.getenv( "RAPL_HOME" );

    public void execute() throws RuntimeException {

        if (packageList == null || packageList.isEmpty()) {
            // without any packageList there is naught to do
            return;
        }

        // create output folder if needed
        try {
            if (outputFolder != null && !outputFolder.isEmpty()) {
                getLog().debug("Storing MANA trace files in: " + outputFolder);
                File output = new File(outputFolder);
                if (!output.exists()) {
                    if (!output.mkdir()) {
                        throw new IOException("Unable to create directory " + outputFolder);
                    }
                }
                if (!output.isDirectory()) {
                    throw new IOException("Parameter <outputFolder> has to be a directory but was " + outputFolder);
                }
            }
        }catch( IOException e ) {
            getLog().error( e );
            throw new RuntimeException( e );
        }

        try {
            project.getRuntimeClasspathElements().forEach(this::appendClassPool);
        } catch (DependencyResolutionRequiredException e) {
            getLog().error(e);
            // not sure if an exception should be thrown here, probably not?

        }

        // extend classpath from resolved dependencies
        /*project.getArtifacts().forEach( a -> {
            getLog().info( a.getFile().getAbsolutePath() );
            try {
                URLClassLoader cpUrlLoader = new URLClassLoader(new URL[]{ a.getFile().toURI().toURL() });
                classPool.insertClassPath( new LoaderClassPath( cpUrlLoader ) );
            }catch( MalformedURLException e ) {
                getLog().error( e );
            }
        } );*/

        List<String> outputDirectory = Arrays.asList(outputDirectories.split(","));
        List<String> packages = Arrays.asList(packageList.split(","));

        // Collect all classes
        Map<String, File> classes = new HashMap<>();

        outputDirectory.forEach(x -> {
            listf(x, x, classes);
            try {
                // Configure the classpool to also look into our output directories
                classPool.appendClassPath(x);
            } catch (NotFoundException e) {
                getLog().error(e);
            }
        });

        // move through every class
        for (Map.Entry<String, File> file : classes.entrySet()) {
            // skip unknown packages and package info classes
            if (packages.isEmpty() || packages.stream().anyMatch(name ->
                    file.getKey().startsWith(name)) && !file.getKey().endsWith("package-info")) {
                try {
                    // identify classfile
                    CtClass ctClass = classPool.get(file.getKey());
                    var altered = false;
                    if ( Modifier.isAbstract(ctClass.getModifiers()) || ctClass.isInterface()) {
                        continue;
                    }

                    // iterate over all methods and instrument valid ones
                    List<CtMethod> methods = Arrays.stream(ctClass.getDeclaredMethods())
                            .filter( m -> !Modifier.isAbstract( m.getModifiers() ) &&
                                            !Modifier.isNative( m.getModifiers() ) &&
                                                !Modifier.isInterface( m.getModifiers() ) ).collect( Collectors.toList() );
                    getLog().info(
                            "├── Processing class - " +
                                    ConsoleColors.CYAN + ctClass.getName() + ConsoleColors.RESET);
                    //getLog().debug( "Methods found for class " + ctClass.getName() + ": " + methods.size() );
                    for( var m : methods ) {
                        if( m.hasAnnotation( AlteredByMana.class )  ) {
                            getLog().warn( "Method " + ConsoleColors.BLUE + m.getName()+ ConsoleColors.RESET + " has already been altered by the Instrument Mana Plugin. Consider executing mvn clean in order to change already altered methods." );
                            continue;
                        }
                        // if it is a test method we instrument for rapl data collection
                        if( m.getName().toLowerCase().contains( "test" )
                                || m.hasAnnotation(TraceMana.class) ) {  // TODO: apply pattern matching
                            getLog().info(
                                    "  ├── Instrumenting method - " +
                                            ConsoleColors.CYAN + m.getLongName() + ConsoleColors.RESET);
                            var filename =  outputFolder == null || outputFolder.isEmpty()
                                    ? ctClass.getName() + "_" + m.getName()
                                    : outputFolder + File.separator + ctClass.getName() + "_" + m.getName();
                            var fileSuffix = ".mana";
                            CtClass raplCommandType = port == -1 ? classPool.getOrNull(RaplJsonDecorator.class.getName()) : classPool.getOrNull(RaplSocketDecorator.class.getName()) ;
                            CtClass raplInternalCommandType = classPool.getOrNull(RaplInternalCommand.class.getName());
                            CtClass raplCommandParameterType = classPool.getOrNull(RaplInternalCommandParameter.class.getName());
                            CtClass raplFileCommandFactory = classPool.getOrNull(FileCommandFactory.class.getName());
                            CtClass raplSocketCommandFactory = classPool.getOrNull(SocketCommandFactory.class.getName());

                            if( raplCommandType != null
                                && raplInternalCommandType != null
                                && raplCommandParameterType != null
                                && raplFileCommandFactory != null
                                && raplSocketCommandFactory != null) {

                                // Get method signature
                                var className = ctClass.getName(); // qualified class name
                                var methodName = m.getName();
                                var methodDesc = m.getMethodInfo().getDescriptor();

                                String commandCreateFactory = String.format( "%s.getInstance().init( \"%s_\" + System.currentTimeMillis() + \".mana\" )", raplFileCommandFactory.getName(), filename );

                                String commandCreateStr = String.format( "%s(new %s(), new java.io.FileWriter(\"%s_\" + System.currentTimeMillis() + \".mana\"))", RaplJsonDecorator.class.getName(), RaplInternalCommand.class.getName(), filename );
                                if( port != -1 ) {
                                    commandCreateStr = String.format( "%s(new %s())", RaplSocketDecorator.class.getName(), RaplInternalCommand.class.getName() );
                                    commandCreateFactory = String.format( "%s.getInstance().init()", raplSocketCommandFactory.getName() );
                                }

                                //String beforeStr = String.format("command = new  %s;command.executeAsync(null,%s.builder().samplingRate(%d).port(%d).className(\"%s\").methodName(\"%s\").methodDesc(\"%s\").build());System.out.println(\"mana-time-sync-start:\" + System.currentTimeMillis() );startTime = System.nanoTime();",
                                //        commandCreateStr, RaplInternalCommandParameter.class.getName(), samplingRate, port, className, methodName, methodDesc );
                                String beforeStr = String.format("%s;%s.getInstance().execute(%s.builder().samplingRate(%d).port(%d).className(\"%s\").methodName(\"%s\").methodDesc(\"%s\").build());System.out.println(\"mana-time-sync-start:\" + System.currentTimeMillis() );startTime = System.nanoTime();",
                                        commandCreateFactory, port != -1 ? raplSocketCommandFactory.getName() : raplFileCommandFactory.getName(), RaplInternalCommandParameter.class.getName(), samplingRate, port, className, methodName, methodDesc );
                                //String afterStr = "command.stopExecute().close();System.out.println(\"mana-time-sync-end:\" + System.currentTimeMillis()); System.out.println(\"mana-duration:\" + ( System.nanoTime() - startTime ) );";
                                String afterStr = String.format("%s.getInstance().stop();System.out.println(\"mana-time-sync-end:\" + System.currentTimeMillis()); System.out.println(\"mana-duration:\" + ( System.nanoTime() - startTime ) );", port != -1 ? raplSocketCommandFactory.getName() : raplFileCommandFactory.getName());
                                m.addLocalVariable("command", raplCommandType );
                                m.addLocalVariable("startTime", CtClass.longType );
                                m.insertBefore( beforeStr );
                                m.insertAfter( afterStr );
                                // ignore methods, that were already processed
                                AnnotationsAttribute attributeNew = genAttribute(AlteredByMana.class, m);
                                m.getMethodInfo().addAttribute( attributeNew );
                                altered = true;
                            } else {
                                getLog().error( "Unable to instrument method, RaplCommand class could not be found." );
                                throw new RuntimeException( "Unable to instrument method, RaplCommand class could not be found." );
                            }
                        } else {
                            if( m.hasAnnotation( AlteredByMana.class )  ) {
                                getLog().warn( "Method " + ConsoleColors.BLUE + m.getName()+ ConsoleColors.RESET + " has already been altered by the Instrument Mana Plugin. Consider executing mvn clean in order to change already altered methods." );
                                continue;
                            }
                            if(trace && !Modifier.isStatic(m.getModifiers())) {
                                var className = ctClass.getName(); // qualified class name
                                var methodName = m.getName();
                                var methodDesc = m.getMethodInfo().getDescriptor();
                                var epoch = "System.currentTimeMillis()";
                                var epochMicroSeconds = "java.time.temporal.ChronoUnit.MICROS.between(java.time.Instant.EPOCH, java.time.Instant.now())";
                                CtClass factoryClass = classPool.getOrNull(FileCommandFactory.class.getName());
                                if( port != -1 )
                                    factoryClass = classPool.getOrNull(SocketCommandFactory.class.getName());

                                m.insertBefore( String.format("%s.getInstance().enter(\"%s\",\"%s\",\"%s\",%s,%s);",
                                        factoryClass.getName(),className, methodName, methodDesc, epoch, epochMicroSeconds) );
                                m.insertAfter( String.format("%s.getInstance().exit(\"%s\",\"%s\",\"%s\",%s,%s);",
                                        factoryClass.getName(),className, methodName, methodDesc, epoch, epochMicroSeconds) );
                                getLog().info("  ├── Instrumenting method: " + ConsoleColors.PURPLE + m.getName() + ConsoleColors.RESET);
                                AnnotationsAttribute attributeNew = genAttribute(AlteredByMana.class, m);
                                m.getMethodInfo().addAttribute( attributeNew );
                                altered = true;
                            }
                        }
                    }

                    // override the class file
                    if( altered ) {
                        DataOutputStream dataOutputStream = new DataOutputStream(new FileOutputStream(file.getValue()));
                        ctClass.toBytecode(dataOutputStream);
                        dataOutputStream.close();
                        getLog().info("├── ✅ Successfully altered class " + ConsoleColors.CYAN + ctClass.getName() + ConsoleColors.RESET );
                    }

                } catch (NotFoundException | IOException | CannotCompileException e) {
                    getLog().error(e);
                }
            }
        }
    }

    private void appendClassPool(String x) {
        try {
            classPool.appendClassPath(x);
        } catch (NotFoundException e) {
            getLog().error(e);
            throw new IllegalStateException(e);
        }
    }


    /**
     * Helper function loading all classes into a file map
     *
     * @param delimiter     what the root folder is
     * @param directoryName folder to load
     * @param files         map to load files into
     */
    public void listf(String delimiter, String directoryName, Map<String, File> files) {
        File directory = new File(directoryName);
        // Get all files from a directory.
        File[] fList = directory.listFiles();
        if (fList != null)
            for (File file : fList) {
                if (file.isFile()) {
                    final String className = file.getAbsolutePath().substring(delimiter.length() + 1, file.getAbsolutePath().length() - ".class".length()).replace(File.separatorChar, '.');
                    files.put(className, file);
                } else if (file.isDirectory()) {
                    listf(delimiter, file.getAbsolutePath(), files);
                }
            }
    }

    private AnnotationsAttribute genAttribute(Class annotationClass, CtMethod method) {
        MethodInfo methodInfoGetEid = method.getMethodInfo();
        ConstPool cp = methodInfoGetEid.getConstPool();
        Annotation annotationNew = new Annotation(annotationClass.getName(), cp);
        AnnotationsAttribute attributeNew = new AnnotationsAttribute(cp, AnnotationsAttribute.invisibleTag);
        attributeNew.addAnnotation(annotationNew);
        return attributeNew;
    }

    public Log getLog() {
        if (this.log == null) {
            this.log = new RaplSystemStreamLog();
        }

        return this.log;
    }

    private class RaplSystemStreamLog implements Log {
        public RaplSystemStreamLog() {
        }

        public void debug(CharSequence content) {
            this.print("DEBUG", content);
        }

        public void debug(CharSequence content, Throwable error) {
            this.print("DEBUG", content, error);
        }

        public void debug(Throwable error) {
            this.print("DEBUG", error);
        }

        public void info(CharSequence content) {
            this.print("\u001B[94mINFO\u001B[0m", content);
        }

        public void info(CharSequence content, Throwable error) {
            this.print("\u001B[94mINFO\u001B[0m", content, error);
        }

        public void info(Throwable error) {
            this.print("\u001B[94mINFO\u001B[0m", error);
        }

        public void warn(CharSequence content) {
            this.print("WARN", content);
        }

        public void warn(CharSequence content, Throwable error) {
            this.print("WARN", content, error);
        }

        public void warn(Throwable error) {
            this.print("WARN", error);
        }

        public void error(CharSequence content) {
            System.err.println("[ERROR] " + content.toString());
        }

        public void error(CharSequence content, Throwable error) {
            StringWriter sWriter = new StringWriter();
            PrintWriter pWriter = new PrintWriter(sWriter);
            error.printStackTrace(pWriter);
            System.err.println("[error] " + content.toString() + "\n\n" + sWriter.toString());
        }

        public void error(Throwable error) {
            StringWriter sWriter = new StringWriter();
            PrintWriter pWriter = new PrintWriter(sWriter);
            error.printStackTrace(pWriter);
            System.err.println("[error] " + sWriter.toString());
        }

        public boolean isDebugEnabled() {
            return false;
        }

        public boolean isInfoEnabled() {
            return true;
        }

        public boolean isWarnEnabled() {
            return true;
        }

        public boolean isErrorEnabled() {
            return true;
        }

        private void print(String prefix, CharSequence content) {
            System.out.println("[" + prefix + "] " + content.toString());
        }

        private void print(String prefix, Throwable error) {
            StringWriter sWriter = new StringWriter();
            PrintWriter pWriter = new PrintWriter(sWriter);
            error.printStackTrace(pWriter);
            System.out.println("[" + prefix + "] " + sWriter.toString());
        }

        private void print(String prefix, CharSequence content, Throwable error) {
            StringWriter sWriter = new StringWriter();
            PrintWriter pWriter = new PrintWriter(sWriter);
            error.printStackTrace(pWriter);
            System.out.println("[" + prefix + "] " + content.toString() + "\n\n" + sWriter.toString());
        }
    }


}
