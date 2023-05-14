package at.mana.instrument;

import at.mana.exec.rapl.RaplCommand;
import at.mana.exec.rapl.internal.*;
import at.mana.exec.rapl.internal.trace.RaplInternalCommandTraceHandler;
import at.mana.exec.rapl.internal.trace.RaplTraceHandler;
import lombok.SneakyThrows;

import java.io.FileWriter;
import java.io.Writer;
import java.util.stream.Collectors;

public class FileCommandFactory {

    private static FileCommandFactory commandFactory;
    private RaplCommand<Writer, RaplInternalCommandParameter> command;
    private RaplTraceHandler<Writer> handler;
    private FileWriter writer;

    public static FileCommandFactory getInstance() {
        if( commandFactory == null )
            commandFactory = new FileCommandFactory();
        return commandFactory;
    }


    private FileCommandFactory() {

    }

    @SneakyThrows
    public FileCommandFactory init( String fileName ) {
        writer = new FileWriter( fileName );
        command = new RaplJsonDecorator( new RaplInternalCommand(), writer );
        handler = new RaplInternalCommandTraceHandler();
        return this;
    }

    @SneakyThrows
    public void execute( RaplInternalCommandParameter parameter ) {
        handler.init( source -> {
            String result = source.stream().map( e ->
                            String.format("{\"methodName\":\"%s\", ", e.getMethodName() ) +
                                    String.format("\"className\":\"%s\", ", e.getClassName() ) +
                                    String.format("\"methodDescriptor\":\"%s\", ", e.getMethodDesc() ) +
                                    String.format("\"hash\":\"%s\", ", e.getHash() ) +
                                    String.format("\"startWall\":\"%s\", ", e.getStartWallTime() ) +
                                    String.format("\"endWall\":\"%s\", ", e.getEndWallTime() ) +
                                    String.format("\"start\":\"%s\", ", e.getStartTime() ) +
                                    String.format("\"end\":\"%s\" }", e.getEndTime() ) )
                    .collect(Collectors.joining(","));
            return String.format( "[%s]", result );
        } );
        command.executeAsync( null, parameter );
        Thread.sleep( parameter.getSamplingRate() * 4L );
    }

    @SneakyThrows
    public void stop() {
        writer.write( "{\"trace\":" );
        handler.finish( writer );
        writer.write( ", \"energy\":" );
        Thread.sleep( 200L );
        command.stopExecute();
        writer.write( "}" );
        writer.close();
    }

    public void enter( String className, String methodName, String methodDesc, long wallTime, long time ){
        handler.enter(className,methodName,methodDesc, wallTime, time);
    }

    public void exit( String className, String methodName, String methodDesc, long wallTime, long time ){
        handler.exit(className,methodName,methodDesc,wallTime, time);
    }


    RaplTraceHandler getHandler() {
        return this.handler;
    }

    RaplCommand<Writer, RaplInternalCommandParameter> getCommand() {
        return this.command;
    }

}
