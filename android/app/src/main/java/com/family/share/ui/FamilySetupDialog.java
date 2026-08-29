package com.family.share.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.family.share.R;
import com.family.share.config.AppConfig;
import com.family.share.data.Prefs;
import com.family.share.network.Api;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

/**
 * 创建/加入家庭对话框。
 * - 首次使用：直接创建或加入
 * - 已在家庭中：作为「切换家庭」入口，提交前需二次确认（避免误操作丢掉原家庭）
 */
public final class FamilySetupDialog {

    public interface Listener {
        /** @param created true=创建家庭，false=加入家庭 */
        void onDone(boolean created, String familyId, String code);

        /** 加入家庭需群主同意，申请已提交（requestId 用于轮询审批结果） */
        void onPending(String requestId);

        void onError(String msg);
    }

    private FamilySetupDialog() {
    }

    public static void show(Activity activity, Listener listener) {
        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_family_setup, null);
        RadioGroup rgMode = v.findViewById(R.id.rgMode);
        RadioButton radioCreate = v.findViewById(R.id.radioCreate);
        EditText etName = v.findViewById(R.id.etName);
        EditText etCode = v.findViewById(R.id.etCode);
        TextView tvCurrentCode = v.findViewById(R.id.tvCurrentCode);

        final Prefs prefs = Prefs.get(activity);
        etName.setText(prefs.deviceName());
        if (!prefs.familyCode().isEmpty()) {
            tvCurrentCode.setText("当前家庭码：" + prefs.familyCode() + "（可分享给家人加入）");
            tvCurrentCode.setVisibility(View.VISIBLE);
        }
        rgMode.setOnCheckedChangeListener((g, id) ->
                etCode.setVisibility(id == R.id.radioJoin ? View.VISIBLE : View.GONE));
        // 同步初始可见性：默认选中「加入家庭」时，家庭码输入框应立即显示（否则需先切走再切回才出现）
        etCode.setVisibility(rgMode.getCheckedRadioButtonId() == R.id.radioJoin ? View.VISIBLE : View.GONE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(v)
                .setPositiveButton(R.string.btn_ok, null)
                .setNegativeButton(R.string.btn_cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(btn -> {
                    final boolean create = radioCreate.isChecked();
                    final String name = etName.getText().toString().trim();
                    final String code = etCode.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(activity, R.string.hint_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!create && code.length() != 6) {
                        Toast.makeText(activity, R.string.hint_family_code, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!prefs.familyId().isEmpty()) {
                        // 已在家庭中：切换前二次确认
                        new MaterialAlertDialogBuilder(activity)
                                .setMessage(activity.getString(R.string.dialog_switch_confirm_message))
                                .setPositiveButton(R.string.dialog_switch_confirm_ok, (d2, w2) ->
                                        submit(activity, dialog, btn, prefs, create, name, code, listener))
                                .setNegativeButton(R.string.btn_cancel, null)
                                .show();
                    } else {
                        submit(activity, dialog, btn, prefs, create, name, code, listener);
                    }
                }));

        dialog.show();
    }

    private static void submit(Activity activity, AlertDialog dialog, View btn, Prefs prefs,
                               boolean create, String name, String code, Listener listener) {
        btn.setEnabled(false);
        Api.Callback cb = new Api.Callback() {
            @Override
            public void onSuccess(String body) {
                activity.runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(body);
                        // 加入家庭需群主同意：服务端返回 pending
                        if (!create && "pending".equals(o.optString("status"))) {
                            prefs.deviceName(name);
                            dialog.dismiss();
                            listener.onPending(o.optString("requestId"));
                            return;
                        }
                        String familyId = o.optString("familyId");
                        String newCode = o.optString("code", code);
                        prefs.deviceName(name);
                        prefs.familyId(familyId);
                        prefs.familyCode(newCode);
                        prefs.isOwner(create);
                        prefs.shareEnabled(true);
                        dialog.dismiss();
                        listener.onDone(create, familyId, newCode);
                    } catch (Exception e) {
                        btn.setEnabled(true);
                        listener.onError("响应解析失败");
                    }
                });
            }

            @Override
            public void onError(String msg) {
                activity.runOnUiThread(() -> {
                    btn.setEnabled(true);
                    listener.onError(msg);
                });
            }
        };
        if (create) {
            Api.createFamily(prefs.deviceId(), name, cb);
        } else {
            Api.joinFamily(code, prefs.deviceId(), name, cb);
        }
    }
}
