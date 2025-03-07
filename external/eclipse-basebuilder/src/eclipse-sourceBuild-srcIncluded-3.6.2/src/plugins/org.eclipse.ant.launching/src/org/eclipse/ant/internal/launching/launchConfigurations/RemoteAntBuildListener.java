/*******************************************************************************
 *  Copyright (c) 2003, 2009 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.ant.internal.launching.launchConfigurations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.tools.ant.Project;
import org.eclipse.ant.internal.core.AbstractEclipseBuildLogger;
import org.eclipse.ant.internal.launching.AntLaunch;
import org.eclipse.ant.internal.launching.AntLaunching;
import org.eclipse.ant.internal.launching.AntLaunchingUtil;
import org.eclipse.ant.internal.launching.IAntLaunchingPreferenceConstants;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchesListener;
import org.eclipse.debug.core.model.IProcess;

/**
 * Parts adapted from org.eclipse.jdt.internal.junit.ui.RemoteTestRunnerClient
 * The client side of the RemoteAntBuildLogger. Handles the marshalling of the
 * different messages.
 */
public class RemoteAntBuildListener implements ILaunchesListener {
	public abstract class ListenerSafeRunnable implements ISafeRunnable {
		public void handleException(Throwable exception) {
			AntLaunching.log(exception);
		}
	}

	/**
	 * The server socket
	 */
	private ServerSocket fServerSocket;
	private Socket fSocket;
	private BufferedReader fBufferedReader;
	private IProcess fProcess;
	private String fProcessId;
	private List fMessageQueue;
	protected ILaunch fLaunch;
	private String fLastFileName = null;
	private String fLastTaskName = null;
	private boolean fBuildFailed = false;

	/**
	 * Reads the message stream from the RemoteAntBuildLogger
	 */
	private class ServerConnection extends Thread {
		private int fServerPort;

		public ServerConnection(int port) {
			super("Ant Build Server Connection"); //$NON-NLS-1$
			setDaemon(true);
			fServerPort = port;
		}

		public void run() {
			Exception exception = null;
			try {
				fServerSocket = new ServerSocket(fServerPort);

				int socketTimeout = Platform
						.getPreferencesService()
						.getInt(
								AntLaunching.getUniqueIdentifier(),
								IAntLaunchingPreferenceConstants.ANT_COMMUNICATION_TIMEOUT,
								20000, null);
				fServerSocket.setSoTimeout(socketTimeout);
				fSocket = fServerSocket.accept();
				fBufferedReader = new BufferedReader(new InputStreamReader(
						fSocket.getInputStream(), "UTF-8")); //$NON-NLS-1$
				String message;
				while (fBufferedReader != null
						&& (message = fBufferedReader.readLine()) != null) {
					receiveMessage(message);
				}
			} catch (SocketException e) {
			} catch (SocketTimeoutException e) {
				exception = e;
			} catch (IOException e) {
				// fall through
				exception = e;
			}
			if (exception != null) {
				AntLaunching.log(exception);
			}
			shutDown();
		}
	}

	public RemoteAntBuildListener(ILaunch launch) {
		super();
		fLaunch = launch;
		DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
	}

	/**
	 * Start listening to an Ant build. Start a server connection that the
	 * RemoteAntBuildLogger can connect to.
	 * 
	 * @param eventPort
	 *            The port number to create the server connection on
	 */
	public synchronized void startListening(int eventPort) {
		ServerConnection connection = new ServerConnection(eventPort);
		connection.start();
	}

	protected synchronized void shutDown() {
		fLaunch = null;
		if (DebugPlugin.getDefault() != null) {
			DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(
					this);
		}
		try {
			if (fBufferedReader != null) {
				fBufferedReader.close();
				fBufferedReader = null;
			}
		} catch (IOException e) {
		}
		try {
			if (fSocket != null) {
				fSocket.close();
				fSocket = null;
			}
		} catch (IOException e) {
		}
		try {
			if (fServerSocket != null) {
				fServerSocket.close();
				fServerSocket = null;
			}
		} catch (IOException e) {
		}
	}

	protected void receiveMessage(String message) {
		if (message.startsWith(MessageIds.TASK)) {
			receiveTaskMessage(message);
		} else if (message.startsWith(MessageIds.TARGET)) {
			receiveTargetMessage(message);
		} else if (message.startsWith(MessageIds.PROCESS_ID)) {
			message = message.substring(MessageIds.PROCESS_ID.length());
			fProcessId = message;
		} else {
			int index = message.indexOf(',');
			if (index > 0) {
				int priority = Integer.parseInt(message.substring(0, index));
				message = message.substring(index + 1);
				writeMessage(
						message + System.getProperty("line.separator"), priority); //$NON-NLS-1$
				if (message.startsWith("BUILD FAILED")) { //$NON-NLS-1$
					fBuildFailed = true;
				} else if (fBuildFailed) {
					if (message.startsWith("Total time:")) { //$NON-NLS-1$
						fBuildFailed = false;
					} else {
						AntLaunchingUtil.linkBuildFailedMessage(message,
								getProcess());
					}
				}

			}
		}
	}

	private void receiveTargetMessage(String message) {
		message = message.substring(MessageIds.TARGET.length());
		StringTokenizer tokenizer = new StringTokenizer(message, ","); //$NON-NLS-1$
		message = tokenizer.nextToken();
		if (tokenizer.hasMoreTokens()) {
			int locationLength = Integer.parseInt(tokenizer.nextToken());
			String location = tokenizer.nextToken();
			while (location.length() < locationLength) { // path with a comma in
				// it
				location += ","; //$NON-NLS-1$
				location += tokenizer.nextToken();
			}
			int lineNumber = Integer.parseInt(tokenizer.nextToken());
			generateLink(message, location, lineNumber, 0, message.length() - 1);
		}
		writeMessage(
				message + System.getProperty("line.separator"), Project.MSG_INFO); //$NON-NLS-1$
	}

	private void receiveTaskMessage(String message) {
		message = message.substring(MessageIds.TASK.length());

		int index = message.indexOf(',');
		int priority = Integer.parseInt(message.substring(0, index));
		int index2 = message.indexOf(',', index + 1);
		String taskName = message.substring(index + 1, index2);
		if (taskName.length() == 0) {
			taskName = fLastTaskName;
		}
		int index3 = message.indexOf(',', index2 + 1);
		int lineLength = Integer
				.parseInt(message.substring(index2 + 1, index3));
		int index4 = index3 + 1 + lineLength;

		String line = message.substring(index3 + 1, index4);
		StringBuffer labelBuff = new StringBuffer();
		labelBuff.append('[');
		labelBuff.append(taskName);
		labelBuff.append("] "); //$NON-NLS-1$
		labelBuff.append(line);
		line = labelBuff.toString();

		fLastTaskName = taskName;

		int locationIndex = message.indexOf(',', index4 + 1);
		int finalIndex = locationIndex + 1;
		String fileName = message.substring(index4 + 1, locationIndex);
		int locationLength = 0;
		if (fileName.length() == 0) {
			fileName = fLastFileName;
		} else {
			finalIndex = message.indexOf(',', locationIndex) + 1;
			locationLength = Integer.parseInt(fileName);
			fileName = message.substring(finalIndex, finalIndex
					+ locationLength);
			locationLength += 1; // set past delimiter
		}
		fLastFileName = fileName;
		int lineNumber = Integer.parseInt(message.substring(finalIndex
				+ locationLength));

		int size = AntLaunching.LEFT_COLUMN_SIZE
				- (taskName.length() + 3);
		int offset = Math.max(size - 2, 1);
		int length = AntLaunching.LEFT_COLUMN_SIZE - size - 3;
		if (fileName != null) {
			generateLink(line, fileName, lineNumber, offset, length);
		}

		StringBuffer fullMessage = new StringBuffer();
		adornMessage(taskName, line, fullMessage);
		writeMessage(
				fullMessage
						.append(System.getProperty("line.separator")).toString(), priority); //$NON-NLS-1$
	}

	private void generateLink(String line, String fileName, int lineNumber,
			int offset, int length) {
		((AntLaunch) fLaunch).addLinkDescriptor(line, fileName, lineNumber,
				offset, length);
	}

	/**
	 * Returns the associated process, finding it if necessary.
	 */
	protected IProcess getProcess() {
		if (fProcess == null) {
			if (fProcessId != null) {
				IProcess[] all = DebugPlugin.getDefault().getLaunchManager()
						.getProcesses();
				for (int i = 0; i < all.length; i++) {
					IProcess process = all[i];
					if (fProcessId
							.equals(process
									.getAttribute(AbstractEclipseBuildLogger.ANT_PROCESS_ID))) {
						fProcess = process;
						break;
					}
				}
			}
		}
		return fProcess;
	}

	private AntStreamMonitor getMonitor(int priority) {
		IProcess process = getProcess();
		if (process == null) {
			return null;
		}
		AntStreamsProxy proxy = (AntStreamsProxy) process.getStreamsProxy();
		if (proxy == null) {
			return null;
		}
		AntStreamMonitor monitor = null;
		switch (priority) {
		case Project.MSG_INFO:
			monitor = (AntStreamMonitor) proxy.getOutputStreamMonitor();
			break;
		case Project.MSG_ERR:
			monitor = (AntStreamMonitor) proxy.getErrorStreamMonitor();
			break;
		case Project.MSG_DEBUG:
			monitor = (AntStreamMonitor) proxy.getDebugStreamMonitor();
			break;
		case Project.MSG_WARN:
			monitor = (AntStreamMonitor) proxy.getWarningStreamMonitor();
			break;
		case Project.MSG_VERBOSE:
			monitor = (AntStreamMonitor) proxy.getVerboseStreamMonitor();
			break;
		}
		return monitor;
	}

	/**
	 * Builds a right justified task prefix for the given build event, placing
	 * it in the given string buffer.
	 * 
	 * @param event
	 *            build event
	 * @param fullMessage
	 *            buffer to place task prefix in
	 */
	private void adornMessage(String taskName, String line,
			StringBuffer fullMessage) {
		if (taskName == null) {
			taskName = "null"; //$NON-NLS-1$
		}

		int size = AntLaunching.LEFT_COLUMN_SIZE
				- (taskName.length() + 6);
		for (int i = 0; i < size; i++) {
			fullMessage.append(' ');
		}

		fullMessage.append(line);
	}

	protected void writeMessage(String message, int priority) {
		AntStreamMonitor monitor = getMonitor(priority);
		if (monitor == null) {
			if (fMessageQueue == null) {
				fMessageQueue = new ArrayList();
			}
			fMessageQueue.add(message);
			return;
		}
		if (fMessageQueue != null) {
			for (Iterator iter = fMessageQueue.iterator(); iter.hasNext();) {
				String oldMessage = (String) iter.next();
				monitor.append(oldMessage);
			}
			fMessageQueue = null;
		}
		monitor.append(message);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.core.ILaunchesListener#launchesAdded(org.eclipse.debug
	 * .core.ILaunch[])
	 */
	public void launchesAdded(ILaunch[] launches) {
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.core.ILaunchesListener#launchesChanged(org.eclipse.
	 * debug.core.ILaunch[])
	 */
	public void launchesChanged(ILaunch[] launches) {
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.debug.core.ILaunchesListener#launchesRemoved(org.eclipse.
	 * debug.core.ILaunch[])
	 */
	public void launchesRemoved(ILaunch[] launches) {
		for (int i = 0; i < launches.length; i++) {
			ILaunch launch = launches[i];
			if (launch.equals(fLaunch)) {
				shutDown();
				return;
			}
		}
	}
}