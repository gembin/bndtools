package bndtools.api.repository;

import java.net.URL;
import java.util.Collection;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import aQute.libg.version.Version;

public interface RemoteRepository {

    String getName();

    /**
     * @param monitor
     *            the progress monitor to use for reporting progress to the
     *            user. It is the caller's responsibility to call done() on the
     *            given monitor. Accepts null, indicating that no progress
     *            should be reported and that the operation cannot be cancelled.
     * @throws CoreException
     */
    void initialise(IProgressMonitor monitor) throws CoreException;

    Collection<String> list(String regex);

    Collection<Version> versions(String bsn);

    URL[] get(String bsn, String range);

}