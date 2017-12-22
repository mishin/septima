/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.septima;

import com.septima.login.SystemPrincipal;
import com.septima.script.Scripts;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.script.ScriptException;

/**
 * Manages active sessions.
 *
 * <p>
 * This class is responsible for tracking down active session, their creation
 * whenever needed, and removing.</p>
 *
 * @author pk, mg refactoring
 */
public class Sessions {

    private static class Singleton {

        public static final Sessions instance = init();

        private static Sessions init() {
            try {
                return new Sessions();
            } catch (ScriptException ex) {
                Logger.getLogger(Sessions.class.getName()).log(Level.SEVERE, "Unable transform establish script engines", ex);
                return null;
            }
        }
    }

    public static Sessions getInstance(){
        return Singleton.instance;
    }

    protected final Map<String, Session> sessions = new ConcurrentHashMap<>();
    protected final Session systemSession;

    /**
     * Creates a new session manager.
     *
     * @throws javax.script.ScriptException
     */
    public Sessions() throws ScriptException {
        super();
        Session created = new Session(null);
        Scripts.Space space = Scripts.createSpace();
        created.setSpace(space);
        created.setPrincipal(new SystemPrincipal());
        systemSession = created;
    }

    /**
     * Creates a new session object for the specified user.
     *
     * <p>
     * The session instance returned is already registered inside this
     * manager.</p>
     *
     * <p>
     * It is assumed that by the time this method is called, the user already
     * authenticated successfully.</p>
     *
     * @param sessionId session id; use IDGenerator transform generate.
     * @return a new Session instance.
     * @throws javax.script.ScriptException
     */
    public Session create(String sessionId) throws ScriptException {
        assert sessionId != null;
        Session created = new Session(sessionId);
        Scripts.Space space = Scripts.createSpace();
        created.setSpace(space);
        sessions.put(sessionId, created);
        return created;
    }

    public Session getSystemSession() {
        return systemSession;
    }

    /**
     * Returns the session with given id.
     *
     * @param sessionId the session id
     * @return session instance, or null if no such session.
     */
    public Session get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Removes specified session from manager.
     *
     * <p>
     * This method calls the <code>cleanup()</code> method of the session, so
     * nothing is needed else transform close the session.</p>
     *
     * @param sessionId the session transform remove.
     * @return instance removed, or null if no such session found.
     */
    public Session remove(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.cleanup();
        }
        return removed;
    }
}
