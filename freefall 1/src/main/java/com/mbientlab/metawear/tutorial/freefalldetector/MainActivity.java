/*
 * Copyright 2015 MbientLab Inc. All rights reserved.
 */

package com.mbientlab.metawear.tutorial.freefalldetector;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.builder.filter.Comparison;
import com.mbientlab.metawear.builder.filter.ThresholdOutput;
import com.mbientlab.metawear.builder.function.Function1;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.GyroBmi160;
import com.mbientlab.metawear.module.GyroBmi160.OutputDataRate;
import com.mbientlab.metawear.module.GyroBmi160.AngularVelocityDataProducer;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Logging;
import com.mbientlab.metawear.data.Acceleration;

import bolts.Continuation;
import bolts.Task;

public class MainActivity extends Activity implements ServiceConnection {

    private static final String LOG_TAG = "fall";
    private MetaWearBoard mwBoard;
    private Accelerometer accelerometer;
    private Debug debug;
    private Logging logging;
    private Ringtone ringtoneSound;
    private boolean enterFall = false;
    private long enterFallTime;
    private boolean hitGround = false;


    // TODO: to be adjusted
    private final double FALL_G_THRESHOLD = 0.5;
    private final double HIT_G_THRESHOLD = 1.9;
    private final long MAX_TIME_GAP = 1000;
    private final long MIN_TIME_GAP = 150;

    //phone call
    private EditText mEditTextNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //phone call stuff
        mEditTextNumber = findViewById(R.id.edit_text_number);
        ImageView imageCall = findViewById(R.id.image_call);

        //imageCall.setOnClickListener(new View.OnClickListener() {
            //@Override
            //public void onClick(View view) {
            //    makePhoneCall();
            //}
        //});

        Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ringtoneSound = RingtoneManager.getRingtone(getApplicationContext(), ringtoneUri);

        getApplicationContext().bindService(new Intent(this, BtleService.class), this, Context.BIND_AUTO_CREATE);


        findViewById(R.id.start_accel).setOnClickListener(v -> {
            //Log.i("<><><> onStart:", "Enter");
            if(accelerometer != null) {
                accelerometer.acceleration().start();
                accelerometer.start();
                updateStatusMessage("In Monitoring....");
            }
            else {
                updateStatusMessage("Accelerometer hasn't been initialized.");
            }
        });
        findViewById(R.id.stop_accel).setOnClickListener(v -> {
            //Log.i("<><><> onStop:", "Enter");
            accelerometer.stop();
            accelerometer.acceleration().stop();

            enterFall = false;
            hitGround = false;

            if (ringtoneSound != null) {
                //Log.i("<><><> onStop:", "Stop Ringtone");
                ringtoneSound.stop();
            }

            updateStatusMessage("");
        });

        findViewById(R.id.reset_board).setOnClickListener(v -> {
            //Log.i("<><><> onReset:", "Enter");

            // try to call bindService to trigger onServiceConnected(), hence reinitilized everything
            getApplicationContext().bindService(new Intent(this, BtleService.class), this, Context.BIND_AUTO_CREATE);
        });
    }

    private void makePhoneCall() {
        String number = mEditTextNumber.getText().toString();
        if(number.trim().length() > 0){
            String dial = "tel:" + number;
            startActivity(new Intent(Intent.ACTION_CALL, Uri.parse(dial)));
        } else{
            Toast.makeText(MainActivity.this, "Enter Phone Number", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        getApplicationContext().unbindService(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        BtleService.LocalBinder serviceBinder = (BtleService.LocalBinder) service;

        String mwMacAddress= "F7:F9:1B:EA:19:D0";   ///< Put your board's MAC address here
        BluetoothManager btManager= (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothDevice btDevice= btManager.getAdapter().getRemoteDevice(mwMacAddress);

        mwBoard= serviceBinder.getMetaWearBoard(btDevice);
        mwBoard.connectAsync().onSuccessTask(task -> {
            accelerometer = mwBoard.getModule(Accelerometer.class);
            accelerometer.configure()
                    .odr(100f)
                    .commit();

            return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                @Override
                public void configure(RouteComponent source) {
                    source.stream(new Subscriber() {
                        @Override
                        public void apply(Data data, Object... env) {

                            Acceleration ac = data.value(Acceleration.class);
                            double totalG = Math.sqrt(ac.x() * ac.x() + ac.y()*ac.y() + ac.z() * ac.z());

                            double LPV = 0.0;

                            Log.i("<><><> Sampling:", data.formattedTimestamp() + ":\n" +
                                    "total G:" + totalG);

                            if (totalG < FALL_G_THRESHOLD) {
                                //first instance of falling below threshold
                                if(!enterFall) {
                                    enterFall = true;
                                    LPV = totalG;
                                    enterFallTime = data.timestamp().getTimeInMillis();
                                }

                                if(enterFall) {
                                    if(totalG < LPV){
                                        LPV = totalG;
                                        enterFallTime = data.timestamp().getTimeInMillis();
                                    }
                                }
                               // Log.i("<><><> Falling:", data.formattedTimestamp() + ":" +
                                    //"total G:" + totalG);
                            }
                            else if (totalG > HIT_G_THRESHOLD) {
                                if (enterFall && (data.timestamp().getTimeInMillis() - enterFallTime < MAX_TIME_GAP) &&
                                        (data.timestamp().getTimeInMillis() - enterFallTime > MIN_TIME_GAP)) {
                                    // it is a legit fall
                                    hitGround = true;

                                    //Log.i("<><><> Landing:", data.formattedTimestamp() + ":" +
                                            //"total G:" + totalG);

                                    // sound alarm
                                    if (ringtoneSound != null) {
                                        ringtoneSound.play();

                                        updateStatusMessage("In Monitoring.... Fall Detected!!!!");
                                    }

                                    //makes phone call
                                    makePhoneCall();

                                }
                            }

                        }
                    });
                }
            });

        }).continueWith((Continuation<Route, Void>) task -> {
            if (task.isFaulted()) {
                Log.e(LOG_TAG, mwBoard.isConnected() ? "Error setting up route" : "Error connecting", task.getError());
                updateBluetoothStatus("Bluetooth connection failed. Try reset or restart the application.");
            } else {
                Log.i(LOG_TAG, "Connected");
                debug = mwBoard.getModule(Debug.class);
                logging= mwBoard.getModule(Logging.class);
                updateBluetoothStatus("Bluetooth connection successful!");
            }

            return null;
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.i(LOG_TAG, "<><><> Disconnected");

    }

    public void updateBluetoothStatus(String toThis) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = (TextView) findViewById(R.id.bluetooth_status);
                textView.setText(toThis);
            }
        });
    }

    public void updateStatusMessage(String toThis) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = (TextView) findViewById(R.id.status_message);
                textView.setText(toThis);
            }
        });
    }
}
