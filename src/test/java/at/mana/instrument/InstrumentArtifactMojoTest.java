package at.mana.instrument;

import org.apache.maven.plugin.testing.AbstractMojoTestCase;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class InstrumentArtifactMojoTest extends AbstractMojoTestCase {


    public void testMojoGoal() throws Exception
    {
        File testPom = new File( getBasedir(), "src/test/resources/basic-test-plugin-config.xml" );
        InstrumentArtifactMojo mojo = (InstrumentArtifactMojo) lookupMojo( "instrument-methods", testPom );
        assertNotNull( mojo );
        assertNotNull( mojo.packageList );
        assertEquals( "at", mojo.packageList );
    }

}
