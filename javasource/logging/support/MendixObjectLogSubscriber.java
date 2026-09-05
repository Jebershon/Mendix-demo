package logging.support;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Date;

import logging.proxies.HasStackTrace;
import logging.proxies.Level;
import logging.proxies.Message;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.logging.LogLevel;
import com.mendix.logging.LogMessage;
import com.mendix.logging.LogSubscriber;
import com.mendix.systemwideinterfaces.core.IContext;

/**
 * {@link LogSubscriber} implementation that logs to Mendix objects of type <i>Logging.Message</i>.
 * <p>
 * This class is of the singleton design, use {@link #getInstance()} to obtain the instance.
 * <p>
 * Ported to Mendix 11.12.x. The runtime logging API used here
 * ({@code LogSubscriber}, {@code LogMessage}, {@code LogLevel}, {@code ILogNode},
 * {@code Core.registerLogSubscriber/createSystemContext/rollback}) is unchanged from the
 * original Mendix 7/8 module and compiles as-is against the Mendix 11 public API.
 *
 * @author Alexander Willemsen - CAPE Groep (original); modernized for Mendix 11
 */
public class MendixObjectLogSubscriber extends LogSubscriber
{
    private final IContext                   context  = Core.createSystemContext();

    private static MendixObjectLogSubscriber instance = null;
    private static LogLevel                  logLevel = LogLevel.INFO;

    private volatile boolean                 stopped  = false;

    /**
     * Creates a new {@link LogSubscriber} that logs to Mendix objects. Constructor is private to
     * prevent external instantiation, use {@link #getInstance()} instead.
     *
     * @param logLevel only messages of this log level or higher are logged ('NONE' disables all
     *            logging)
     */
    private MendixObjectLogSubscriber(final LogLevel logLevel)
    {
        super(MendixObjectLogSubscriber.class.getName(), logLevel);
    }

    /**
     * Returns the singleton instance of this class. If you want to set the log level (default is
     * 'INFO'), make sure to do this prior to the first call to this method, see
     * {@link #setLogLevel(LogLevel)}.
     *
     * @return {@link MendixObjectLogSubscriber} instance
     */
    public static synchronized MendixObjectLogSubscriber getInstance()
    {
        if (MendixObjectLogSubscriber.instance == null) {
            MendixObjectLogSubscriber.instance = new MendixObjectLogSubscriber(MendixObjectLogSubscriber.logLevel);
        }
        return MendixObjectLogSubscriber.instance;
    }

    /**
     * Sets the log level of this class. Only messages of this log level or higher are logged
     * ('NONE' disables all logging). The default log level is 'INFO'.
     * <p>
     * This (static) method can only be called <i>before</i> this class is initialized, i.e. before
     * the first call to {@link #getInstance()}.
     *
     * @param logLevel only messages of this log level or higher are logged ('NONE' disables all
     *            logging)
     */
    public static synchronized void setLogLevel(final LogLevel logLevel)
    {
        if (MendixObjectLogSubscriber.instance != null) {
            throw new IllegalStateException("Log level cannot be set, because this class is already intitialized. "
                    + "Make sure to set the log level before the first call to getInstance().");
        }
        if (logLevel == null) {
            throw new IllegalArgumentException("Parameter 'logLevel' must not be null.");
        }
        MendixObjectLogSubscriber.logLevel = logLevel;
    }

    /**
     * Stops the logging to Mendix objects by this class. Logging can be resumed with {@link #start()}.
     */
    public void stop()
    {
        this.stopped = true;
    }

    /**
     * Resumes logging to Mendix objects after a previous call to {@link #stop()}. Has no effect when
     * logging is already running.
     */
    public void start()
    {
        this.stopped = false;
    }

    /*
     * (non-Javadoc)
     * @see com.mendix.logging.LogSubscriber#processMessage(com.mendix.logging.LogMessage)
     */
    @Override
    public void processMessage(final LogMessage logMessage)
    {
        if (!this.stopped) {
            Message message = null;
            try {
                message = new Message(this.context);
                message.setTimestamp(new Date(logMessage.timestamp));
                message.setLevel(this.convertLevel(logMessage.level));
                message.setNode(this.convertNode(logMessage.node));
                message.setMessage(this.convertMessage(logMessage.message));
                message.setStackTrace(this.convertCause(logMessage.cause));
                message.setHasStackTrace(logMessage.cause != null ? HasStackTrace.YES : HasStackTrace.NO);
                message.commit();
            }
            catch (final Exception ex1) {
                if (message != null) {
                    try {
                        Core.rollback(this.context, message.getMendixObject());
                    }
                    catch (final Exception ex2) {
                        // do nothing: logging shouldn't cause exceptions that might affect the application
                    }
                }
            }
        }
    }

    private Level convertLevel(final LogLevel level)
    {
        if (level == null) {
            return null;
        }
        try {
            return Level.valueOf(level.name());
        }
        catch (final Exception ex) {
            return null;
        }
    }

    private String convertNode(final ILogNode node)
    {
        if (node == null) {
            return null;
        }
        final String nodeName = node.name();
        if ((nodeName != null) && (nodeName.length() > 128)) {
            return nodeName.substring(0, 128);
        }
        return nodeName;
    }

    private String convertMessage(final Object message)
    {
        if (message == null) {
            return null;
        }
        return message.toString();
    }

    private String convertCause(final Throwable cause)
    {
        if (cause == null) {
            return null;
        }
        final Writer stringWriter = new StringWriter();
        cause.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
