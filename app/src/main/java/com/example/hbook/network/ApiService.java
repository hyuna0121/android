package com.example.hbook.network;

import com.example.hbook.model.OcrResponse;
import com.example.hbook.model.TtsRequest;
import com.example.hbook.model.TtsResponse;

import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {
    @Multipart     // 요청이 파일(사진)을 포함하고 있음
    @POST("api/scan")
    Call<OcrResponse> uploadImage(
            @Part MultipartBody.Part image,
            @Part("page_number") RequestBody pageNumber
    );

    @Multipart
    @POST("api/scan")
    Call<OcrResponse> uploadMultipleImages(
            @Part List<MultipartBody.Part> images,
            @Part List<MultipartBody.Part> corners,
            @Part("page_number") RequestBody pageNumber
    );

    @Multipart
    @POST("api/preview-crop")
    Call<ResponseBody> previewCrop(
            @Part List<MultipartBody.Part> images,
            @Part List<MultipartBody.Part> corners
    );

    @POST("api/tts")
    Call<TtsResponse> generateTts(@Body TtsRequest request);
}
