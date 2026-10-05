package com.example.saudi_anpr.api;

import com.google.gson.annotations.SerializedName;

public class ScheduleResponse {
    public boolean success;
    public ScheduleData data;

    public static class ScheduleData {
        public ScheduleItem active;
        public ScheduleItem upcoming;

        @SerializedName("current_time")
        public String currentTime;
    }

    public static class ScheduleItem {
        public String location;

        @SerializedName("start_time")
        public String startTime;

        @SerializedName("end_time")
        public String endTime;

        public double latitude;
        public double longitude;
    }
}