package org.powerbot.misc;

import java.awt.AWTPermission;
import java.awt.Desktop;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.net.InetAddress;
import java.security.Permission;
import java.util.Map.Entry;
import java.util.logging.Logger;

import org.powerbot.Boot;
import org.powerbot.Configuration;
import org.powerbot.bot.loader.GameClassLoader;
import org.powerbot.script.internal.ScriptClassLoader;
import org.powerbot.script.internal.ScriptThreadFactory;
import org.powerbot.script.methods.Keyboard;
import org.powerbot.util.StringUtils;

public class Sandbox extends SecurityManager {
	private static final Logger log = Logger.getLogger("Sandbox");

	@Override
	public void checkConnect(final String host, final int port) {
		checkConnect(host, port, null);
	}

	@Override
	public void checkConnect(final String host, final int port, final Object context) {
		if (isGameThread()) {
			return;
		}
		if (context == null) {
			super.checkConnect(host, port);
		} else {
			super.checkConnect(host, port, context);
		}
	}

	@Override
	public void checkCreateClassLoader() {
		if (isScriptThread() && !isCallingClass(javax.swing.UIDefaults.class, java.io.ObjectOutputStream.class, java.io.ObjectInputStream.class,
				java.lang.reflect.Proxy.class, Keyboard.class)) {
			log.severe("Creating class loader denied");
			throw new SecurityException();
		}
		super.checkCreateClassLoader();
	}

	@Override
	public void checkExec(final String cmd) {
		if (isScriptThread()) {
			throw new SecurityException();
		}
		super.checkExec(cmd);
	}

	@Override
	public void checkExit(final int status) {
		if (isScriptThread()) {
			throw new SecurityException();
		}
		super.checkExit(status);
	}

	@Override
	public void checkMulticast(final InetAddress maddr) {
		throw new SecurityException();
	}

	@Override
	@SuppressWarnings("deprecation")
	public void checkMulticast(final InetAddress maddr, final byte ttl) {
		throw new SecurityException();
	}

	@Override
	public void checkPermission(final Permission perm) {
		final String name = perm.getName();

		if (perm instanceof RuntimePermission) {
			if (name.equals("setSecurityManager") || (name.equals("setContextClassLoader") && isScriptThread() && !isCallingClass(ScriptThreadFactory.class))) {
				throw new SecurityException(name);
			}
		} else if (perm instanceof AWTPermission) {
			if (name.equals("showWindowWithoutWarningBanner") && isScriptThread()) {
				throw new SecurityException();
			}
		} else if (perm instanceof FilePermission) {
			final FilePermission fp = (FilePermission) perm;
			final String a = fp.getActions();
			if (isCallingClass(Desktop.class)) {
				return;
			}
			if (a.equals("execute") && isCallingClass(Boot.class)) {
				return;
			}
			checkFilePath(fp.getName(), a.equalsIgnoreCase("read") || a.equalsIgnoreCase("readlink"));
		}
	}

	@Override
	public void checkPermission(final Permission perm, final Object context) {
		checkPermission(perm);
	}

	@Override
	public void checkPrintJobAccess() {
		throw new SecurityException();
	}

	@Override
	public void checkSetFactory() {
		if (isScriptThread()) {
			throw new SecurityException();
		}
		super.checkSetFactory();
	}

	@Override
	public void checkSystemClipboardAccess() {
		if (isCallingClass(java.awt.event.InputEvent.class)) {
			return;
		}
		throw new SecurityException();
	}

	private void checkFilePath(final String pathRaw, final boolean readOnly) {
		if (Configuration.OS == Configuration.OperatingSystem.WINDOWS) {
			final Class<?>[] ctx = getClassContext();
			int n = 2;
			for (int i = n; i < ctx.length; i++) {
				final String a = ctx[i].getName();
				if (a.equals("java.io.Win32FileSystem")) {
					n = i;
					break;
				}
			}
			if (++n < ctx.length && ctx[n].getName().equals(File.class.getName())) {
				return;
			}
		}

		final String path = getCanonicalPath(new File(StringUtils.urlDecode(pathRaw))), tmp = getCanonicalPath(Configuration.TEMP);
		// permission controls for crypt files
		for (final Entry<File, Class<?>[]> entry : CryptFile.PERMISSIONS.entrySet()) {
			final Class<?>[] entries = new Class<?>[entry.getValue().length + 1];
			entries[0] = CryptFile.class;
			System.arraycopy(entry.getValue(), 0, entries, 1, entries.length - 1);
			final String pathDecoded = getCanonicalPath(new File(StringUtils.urlDecode(entry.getKey().getAbsolutePath())));
			if (pathDecoded.equals(path)) {
				if (!isCallingClass(entries)) {
					throw new SecurityException();
				}
			}
		}

		if ((path + File.separator).startsWith(Configuration.HOME.getAbsolutePath()) &&
				isCallingClass(NetworkAccount.class, GameAccounts.class, Tracker.class, CryptFile.class)) {
			return;
		}

		// allow access for privileged thread groups
		if (isGameThread()) {
			return;
		}

		// allow read permissions to all files
		if (readOnly) {
			return;
		}

		// allow write access to temp directory
		if ((path + File.separator).startsWith(tmp)) {
			return;
		}

		// allow jrebel for debugging
		if (!Configuration.FROMJAR && new File(path).getParentFile().getName().equals(".jrebel")) {
			return;
		}

		throw new SecurityException((readOnly ? "read" : "write") + ": " + path);
	}

	private static String getCanonicalPath(final File f) {
		try {
			return f.getCanonicalPath();
		} catch (final IOException ignored) {
			return f.getAbsolutePath();
		}
	}

	private boolean isCallingClass(final Class<?>... classes) {
		for (final Class<?> context : getClassContext()) {
			for (final Class<?> clazz : classes) {
				if (clazz.isAssignableFrom(context)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isScriptThread() {
		return Thread.currentThread().getContextClassLoader() instanceof ScriptClassLoader;
	}

	public static boolean isGameThread() {
		return Thread.currentThread().getContextClassLoader() instanceof GameClassLoader;
	}
}