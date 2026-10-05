package com.example.saudi_anpr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import android.content.Intent;
import android.provider.Settings;

import android.speech.tts.TextToSpeech;


public class ReminderActivity extends AppCompatActivity
        implements LocationListener {


    public static final String EXTRA_TITLE = "reminder_title";
    public static final String EXTRA_LOCATION = "reminder_location";
    public static final String EXTRA_MESSAGE = "reminder_message";
    public static final String EXTRA_START_TIME = "reminder_start_time";
    public static final String EXTRA_LATITUDE = "reminder_latitude";
    public static final String EXTRA_LONGITUDE = "reminder_longitude";


    private static final int LOCATION_PERMISSION_REQUEST = 401;


    private TextView tvReminderTitle;
    private TextView tvDestination;
    private TextView tvRouteInfo;
    private TextView tvGpsStatus;

    private Button btnNavigate;
    private Button btnClose;


    private WebView mapWebView;


    private LocationManager locationManager;
    private Location currentLocation;


    private double destinationLatitude;
    private double destinationLongitude;
    private String destinationName;


    private boolean navigationActive = false;


    private Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;



    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_reminder_navigation);


        tvReminderTitle = findViewById(R.id.tvReminderTitle);
        tvDestination = findViewById(R.id.tvDestination);
        tvRouteInfo = findViewById(R.id.tvRouteInfo);
        tvGpsStatus = findViewById(R.id.tvGpsStatus);


        btnNavigate = findViewById(R.id.btnNavigate);
        btnClose = findViewById(R.id.btnClose);


        mapWebView = findViewById(R.id.mapWebView);



        String title =
                getIntent().getStringExtra(EXTRA_TITLE);


        destinationName =
                getIntent().getStringExtra(EXTRA_LOCATION);

        tts = new TextToSpeech(this, status -> {

            if(status == TextToSpeech.SUCCESS){

                tts.setLanguage(Locale.US);

                tts.speak(
                        "Navigation started to " + destinationName,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "NAVIGATION_START"
                );

            }

        });


        destinationLatitude =
                getIntent().getDoubleExtra(EXTRA_LATITUDE,0);


        destinationLongitude =
                getIntent().getDoubleExtra(EXTRA_LONGITUDE,0);



        String time =
                new java.text.SimpleDateFormat(
                        "hh:mm a",
                        Locale.US
                ).format(new java.util.Date());


        tvReminderTitle.setText(time);


        tvDestination.setText(
                destinationName == null ?
                        "Destination" :
                        destinationName
        );




        tvRouteInfo.setText(
                String.format(
                        Locale.US,
                        "DEST %.5f , %.5f",
                        destinationLatitude,
                        destinationLongitude
                )
        );


        tvGpsStatus.setText(
                "Starting GPS..."
        );


        btnNavigate.setText("START");


        setupMap();


        locationManager =
                (LocationManager)getSystemService(LOCATION_SERVICE);



        btnNavigate.setOnClickListener(v -> {

            navigationActive = !navigationActive;


            if(navigationActive){

                btnNavigate.setText("STOP");

            }else{

                btnNavigate.setText("START");

            }

        });



        btnClose.setOnClickListener(v -> finish());



        checkPermissionAndStartGPS();

    }




    private void setupMap(){

        mapWebView.getSettings().setJavaScriptEnabled(true);

        String html =
                "<html>" +
                        "<head>" +

                        "<link rel='stylesheet' href='https://unpkg.com/leaflet/dist/leaflet.css'/>" +

                        "<script src='https://unpkg.com/leaflet/dist/leaflet.js'></script>" +

                        "</head>" +

                        "<body style='margin:0'>" +

                        "<div id='map' style='width:100%;height:100vh'></div>" +

                        "<script>" +

                        "var map=L.map('map').setView([24.7577671,46.5926873],15);" +

                        "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);" +

                        "L.marker([24.7577671,46.5926873]).addTo(map).bindPopup('Sports Boulevard').openPopup();" +

                        "L.marker(["
                        + destinationLatitude + "," + destinationLongitude +
                        "]).addTo(map).bindPopup('Destination');" +

                        "</script>" +

                        "</body></html>";


        mapWebView.loadDataWithBaseURL(
                "https://unpkg.com",
                html,
                "text/html",
                "UTF-8",
                null
        );

    }




    private void checkPermissionAndStartGPS(){


        // Check GPS enabled
        boolean gpsEnabled =
                locationManager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                );


        if(!gpsEnabled){

            tvGpsStatus.setText(
                    "GPS OFF - Enable Location"
            );


            Intent intent =
                    new Intent(
                            Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    );

            startActivity(intent);

            return;
        }



        // Check permission
        if(ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        )
                != PackageManager.PERMISSION_GRANTED){


            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );


            return;
        }


        tvGpsStatus.setText(
                "GPS ACTIVE - Searching..."
        );


        startGPS();

    }





    @Override
    public void onLocationChanged(
            @NonNull Location location){


        currentLocation = location;


        tvGpsStatus.setText(
                String.format(
                        Locale.US,
                        "GPS FIX %.6f , %.6f",
                        location.getLatitude(),
                        location.getLongitude()
                )
        );


        tvRouteInfo.setText(
                String.format(
                        Locale.US,
                        "DEST %.5f , %.5f",
                        destinationLatitude,
                        destinationLongitude
                )
        );
    }




    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] results){


        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                results
        );


        if(requestCode==LOCATION_PERMISSION_REQUEST){

            startGPS();

        }

    }





    @Override
    protected void onPause(){

        super.onPause();


        if(locationManager!=null){

            locationManager.removeUpdates(this);

        }

    }




    @Override
    protected void onResume(){

        super.onResume();


        if(locationManager!=null){

            checkPermissionAndStartGPS();

        }

    }

    private void startGPS(){

        try {

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    1,
                    this,
                    Looper.getMainLooper()
            );


            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,
                    3,
                    this,
                    Looper.getMainLooper()
            );


        } catch(SecurityException e){

            tvGpsStatus.setText(
                    "GPS permission error"
            );

        }

    }



    @Override
    public void onProviderEnabled(@NonNull String provider){}


    @Override
    public void onProviderDisabled(@NonNull String provider){}


    @Override
    public void onStatusChanged(
            String provider,
            int status,
            Bundle extras){}

    @Override
    protected void onDestroy(){

        if(tts != null){
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }

}