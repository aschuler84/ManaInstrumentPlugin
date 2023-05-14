package at.mana.instrument;

import at.mana.exec.rapl.RaplCommand;
import at.mana.exec.rapl.internal.RaplInternalCommand;
import at.mana.exec.rapl.internal.RaplInternalCommandParameter;
import at.mana.exec.rapl.internal.RaplSocketDecorator;
import at.mana.exec.rapl.internal.trace.RaplInternalCommandTraceHandler;
import at.mana.exec.rapl.internal.trace.RaplTraceHandler;
import lombok.SneakyThrows;

import java.io.Writer;
import java.net.Socket;
import java.util.stream.Collectors;

public class SocketCommandFactory {

    private static SocketCommandFactory commandFactory;
    private RaplCommand<Socket, RaplInternalCommandParameter> command;
    private RaplTraceHandler<Writer> handler;

    public static SocketCommandFactory getInstance() {
        if( commandFactory == null )
            commandFactory = new SocketCommandFactory();
        return commandFactory;
    }

    private SocketCommandFactory() {
    }

    public void init() {
        command = new RaplSocketDecorator( new RaplInternalCommand() ) {
            @Override
            @SneakyThrows
            public Socket stopExecute() {
                writer.write( "{\"trace\":" );
                handler.finish(writer);
                writer.write( ", \"energy\":" );
                decorated.stopExecute();
                writer.write( "}" );
                writer.write(TERMINATE_SYM);
                writer.flush();
                socket.close();
                return socket;
            }
        };
        handler = new RaplInternalCommandTraceHandler();
    }

    public void enter( String className, String methodName, String methodDesc, long wallTime, long time ){
        handler.enter(className,methodName,methodDesc,wallTime, time);
    }

    public void exit( String className, String methodName, String methodDesc, long wallTime, long time ){
        handler.exit(className,methodName,methodDesc, wallTime, time);
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
                        String.format("\"endWall\":\"%s\", ", e.getEndWallTime() )  +
                        String.format("\"start\":\"%s\", ", e.getStartTime() )  +
                        String.format("\"end\":\"%s\" }", e.getEndTime() ) )
                    .collect(Collectors.joining(","));
            return String.format( "[%s]", result );
        } );
        command.executeAsync( null, parameter );
        Thread.sleep( (parameter.getSamplingRate() * 4L) );  // wait for 4 sample periods before proceeding
    }

    @SneakyThrows
    public void stop( ) {
        Thread.sleep( 200L );  // wait for 4 sample periods before proceeding
        command.stopExecute();
    }

    RaplTraceHandler getHandler() {
        return this.handler;
    }

    RaplCommand<Socket, RaplInternalCommandParameter> getCommand() {
        return this.command;
    }


}
