package github.luv.mockgeofix;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Vector;

import github.luv.mockgeofix.util.ResponseWriter;

/**
 * MockLocationService starts at the app startup and runs as long as the application process
 * is running (never call stopService on this service). This service does *not* correspond to
 * the worker thread (MockLocationThread), this is a management service that is used to start/stop
 * the worker thread.
 *
 * In addition to starting/stopping the worker thread on demand, MockLocationService also
 * broadcasts when the thread has been started/stopped or an error occurred, it also starts/stops
 * the ForegroundService and OomAdjOverrider
 */
public class MockLocationService extends Service {
    String TAG = "MockLocationService";

    protected MockLocationThread mThread = null;
    protected SharedPreferences pref = null;
    protected OomAdjOverrider oomAdjOverrider = null;

    public final static String STARTED = "STARTED";
    public final static String STOPPED = "STOPPED";
    public final static String ERROR = "ERROR";
    protected String lastErr;

    public class Binder extends android.os.Binder {
        MockLocationService getService() {
            return MockLocationService.this;
        }
    }

    private final IBinder mBinder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        pref = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // In Android docs it says "START_NOT_STICKY" means that the service won't be restarted when
        // killed by OOM/low-memory killer, but the OOM/low-memory killer does not just stops the
        // service but kills the whole process (ie kills MockGeoFix app). When the documentation talks
        // about restarting the service it effectively means restarting the process in which the
        // service runs.
        //
        // This is a management service that has to run only as long as MockGeoFix app is running so
        // we don't require (or want) the oom-killer to restart MockGeoFix app only because this
        // service was running when it was killed
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /* interface to be used by clients */
    public boolean isRunning() {
        return !(mThread == null);
    }

    public String getLastErr() { return lastErr; }

    public void start() {
        if (mThread == null) {
            mThread = new MockLocationThread(getApplicationContext(), this );
            mThread.start();
        }
    }

    public void stop() {
        if (mThread == null) { return; }
        mThread.kill();
        mThread.interrupt();
    }
    /* end of interface */

    /* methods used by MockLocationThread
       to communicate its' state */
    protected void threadHasStopped() {
        mThread = null;
        Intent i = new Intent(getApplicationContext(), ForegroundService.class);
        // it's fine to call stopService on a service that is not running
        stopService(i);
        // if monitoring is already stopped, this call has no effect.
        if (oomAdjOverrider != null) {
            oomAdjOverrider.stop();
            oomAdjOverrider = null;
        }
        broadcast(STOPPED);
    }

    protected void threadHasStartedSuccessfully() {
        if (pref.getBoolean("foreground_service", false)) {
            Intent i = new Intent(getApplicationContext(), ForegroundService.class);
            startService(i);
        }
        if (pref.getBoolean("oom_adj",false)) {
            // creating new OomAdjOverrider instance everytime the worker has started
            // means phoneNotRooted attribute is reset - but that's what we want - someone might
            // have changed "Root Access" setting in cyanogenmod for example
            oomAdjOverrider = new OomAdjOverrider(-13) {
                @Override
                public synchronized void suErrorHandler(RunException ex) {
                    errorHasOccurred(ex.getMessage());
                    phoneNotRooted = true;
                }
            };
            oomAdjOverrider.start();
        }
        broadcast(STARTED);
    }

    protected void errorHasOccurred(String err) {
        lastErr = err;
        broadcast(ERROR);
    }
    protected Vector<SocketAddress> getBindAddresses() {
        Vector<SocketAddress> ret = new Vector<>();

        /* retrieve port settings from preferences */
        String portStr = pref.getString("listen_port", "5554");
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("Invalid port number.");
            }
        } catch (NumberFormatException ex) {
            Log.e(TAG, String.format("Invalid port number %s. Defaulting to 5554", portStr));
            errorHasOccurred(String.format("Invalid port number %s. Defaulting to 5554.",
                    portStr));
            port = 5554;
        }

        /* retrieve interfaces to listen on, set port and return a vector of SocketAddresses */
        String ifaceName = pref.getString("listen_ip","ALL");

        if (ifaceName.equals("ALL")) {
            ret.add( new InetSocketAddress(port) );
            return ret;
        }

        try {
            NetworkInterface iface = NetworkInterface.getByName(ifaceName);
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                ret.add(new InetSocketAddress(address, port));
            }
        } catch(SocketException ex) {
            Log.e(TAG, "SocketException thrown (getBindAddress)");
        } catch (NullPointerException ex) {
            Log.e(TAG, "NullPointerException thrown (getBindAddress)");
        }
        return ret;
    }

    /* helper methods */
    protected void broadcast(String msg) {
        Intent i = new Intent();
        i.setAction(msg);
        sendBroadcast(i);
    }
}

class MockLocationThread extends Thread {
    String TAG = "MockLocationService.Thread";

    protected Context mContext;
    protected MockLocationService mService;
    protected Vector<SocketAddress> mBindAddresses;

    public MockLocationThread(Context context, MockLocationService service) {
        mContext = context;
        mService = service;
        PowerManager mgr = (PowerManager)context
                .getSystemService(Context.POWER_SERVICE);
        mWakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MockGeoFixWakeLock");
    }
    private boolean mStop = false;

    private HashMap<Socket, Boolean> mClientDiscard = new HashMap<>();
    private HashMap<Socket, ByteBuffer> mClientBuffers = new HashMap<>();

    protected PowerManager.WakeLock mWakeLock = null;

    public void kill() {
        mStop = true;
    }

    @SuppressWarnings({"InfiniteLoopStatement", "EmptyCatchBlock"})
    @Override
    public void run() {
        super.run();
        Selector selector = null;
        try {
            selector = Selector.open();
            mBindAddresses = mService.getBindAddresses();
            for (SocketAddress address : mBindAddresses) {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.socket().setReuseAddress(true);
                ssc.socket().bind(address);
                ssc.register(selector, SelectionKey.OP_ACCEPT);
            }
            try {
                mWakeLock.acquire();
            } catch (SecurityException ex) {
                Log.e(TAG, "WakeLock not acquired - permission denied for WakeLock.");
            }

            if (Build.VERSION.SDK_INT < 23) {
                if (mContext.checkCallingOrSelfPermission("android.permission.ACCESS_MOCK_LOCATION")
                    != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Permission denied for android.permission.ACCESS_MOCK_LOCATION");
                }
            }
            MockLocationProvider.register();
            mService.threadHasStartedSuccessfully();

            while (!mStop) {
                selector.select();
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isAcceptable() && key.channel() instanceof ServerSocketChannel) {
                        // accept connection
                        SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
                        if (client != null) {
                            client.configureBlocking(false);
                            client.socket().setTcpNoDelay(true);
                            client.register(selector, SelectionKey.OP_READ);
                            ResponseWriter.writeLine(client, "MockGeoFix: type 'help' for a list of commands");
                            ResponseWriter.ok(client);
                        }
                    }
                    if (key.isReadable() && key.channel() instanceof SocketChannel) {
                        onIncomingData((SocketChannel) key.channel());
                    }
                }
                selector.selectedKeys().clear();
            }
        } catch (SocketException e) {
            Log.e(TAG, e.toString() );
            // most common errors
            if (e.getClass() == BindException.class && e.getMessage().contains("EADDRINUSE")) {
                mService.errorHasOccurred(mContext.getString(R.string.err_address_already_in_use));
            } else if (e.getClass() == BindException.class && e.getMessage().contains("EACCES") ) {
                mService.errorHasOccurred(mContext.getString(R.string.err_socket_permission_denied));
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString() );
        } catch (SecurityException e) {
            Log.e(TAG, e.toString() );
            mService.errorHasOccurred(mContext.getString(R.string.err_access_mock_location));
        } finally {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            MockLocationProvider.unregister();
            if (selector != null) {
                for (SelectionKey key : selector.keys()) {
                    try { key.channel().close(); } catch (IOException ignored) {}
                }
                try { selector.close(); } catch (IOException ignored) {}
            }
            mService.threadHasStopped();
        }
    }

    void onIncomingData(SocketChannel client) {
        // geo nmea sentences max length is about 80 characters
        // geo fix messages are very short as well
        // 2048 should be enough.
        ByteBuffer buffer = mClientBuffers.get(client.socket());
        if (buffer == null) {
            ByteBuffer newBuffer = ByteBuffer.allocate(2048);
            mClientBuffers.put(client.socket(), newBuffer);
            mClientDiscard.put(client.socket(), Boolean.FALSE);
            buffer = newBuffer;
        }

        /* read new data into buffer */
        int bytesRead;
        try {
            bytesRead = client.read(buffer);
        } catch (IOException ex) {
            try { client.close(); } catch (IOException ignored) {}
            return;
        }
        if (bytesRead == -1) {
            try { client.close(); } catch (IOException ignored) {}
            return;
        }

        /* process data in buffer */
        processBuffer(client, buffer);

    }

    void processBuffer(SocketChannel client, ByteBuffer buffer) {
        Boolean discard = mClientDiscard.get(client.socket());

        /* process buffer */
        if (discard) {
            byte[] line = getLine(buffer);
            if (line != null) {
                mClientDiscard.put(client.socket(), Boolean.FALSE);
            } else {
                buffer.clear();
                return;
            }
        }

        byte[] line = getLine(buffer);
        while (line != null) {
            // process line
            try {
                String command = new String(line, "UTF-8").replace("\r\n","").replace("\n","");
                CommandDispatcher.dispatch(client, command);
            } catch (UnsupportedEncodingException ignored) {}
            line = getLine(buffer);
        }
        if (!buffer.hasRemaining()) {
            // process all buffer as if line
            buffer.flip();
            line = new byte[buffer.limit()];
            buffer.get(line);
            try {
                String command = new String(line, "UTF-8").replace("\r\n","").replace("\n","");
                CommandDispatcher.dispatch(client, command);
            } catch (UnsupportedEncodingException ignored) {}
            buffer.clear();
            mClientDiscard.put(client.socket(), Boolean.TRUE);
        }
    }

    static byte[] getLine(ByteBuffer bfr) {
        // note: duplicate copies only bytebuffer metadata (such as position,
        // limit, capicity) and not the actual content
        ByteBuffer buffer = bfr.duplicate();
        int last = buffer.position();
        buffer.flip();
        int nPos = -1; // position of \n in the buffer
        for (int i=0; i < last; i++) {
            int currChar = buffer.get();
            // 0x0a = \n; 0x0D = \r
            if (currChar == 0x0A) {
                nPos = i;
                break;
            }
        }
        if (nPos == -1) {
            return null;
        } else {
            byte[] ret = new byte[nPos+1];
            // we want to use the ORIGINAL buffer here
            bfr.flip();
            bfr.get(ret);
            bfr.compact();
            return ret;
        }
    }
}


