package com.example.cinemacamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import java.util.Arrays;

/** Reports actual camera API declarations, never guesses RAW support from model names. */
final class CameraCapabilities {
    private CameraCapabilities() {}

    static String describe(Context context) {
        StringBuilder result = new StringBuilder(Build.MANUFACTURER+" "+Build.MODEL
            +"\nAndroid "+Build.VERSION.RELEASE+"\n\n");
        result.append("処理方針：RAW優先 → 端末内現像 → ルック適用 → JPEG保存\n")
            .append("RAW非対応・取得/現像失敗時はJPEG代替。以下は端末の申告能力で、RAW撮影の成功確認ではありません。\n\n");
        try {
            CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            for(String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                int[] capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean raw = capabilities != null && Arrays.stream(capabilities)
                    .anyMatch(value -> value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);
                StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.RAW_SENSOR);
                result.append("カメラ ").append(id).append(back ? "（背面）" : "（その他）").append('\n')
                    .append("RAW capability: ").append(raw).append('\n')
                    .append("RAW_SENSOR sizes: ").append(sizes == null ? "なし" : Arrays.toString(sizes)).append('\n')
                    .append("Hardware level: ").append(info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)).append('\n')
                    .append("Sensor white level: ").append(info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).append('\n')
                    .append("Color matrix: ").append(info.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null).append('\n')
                    .append("Tone-map modes: ").append(Arrays.toString(info.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)))
                    .append("\n\n");
            }
        } catch(Exception error) {
            result.append("取得できませんでした：").append(error.getClass().getSimpleName());
        }
        return result.toString();
    }
}
