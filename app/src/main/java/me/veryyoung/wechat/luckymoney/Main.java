package me.veryyoung.wechat.luckymoney;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static android.text.TextUtils.isEmpty;
import static android.widget.Toast.LENGTH_LONG;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findFirstFieldByExactType;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static me.veryyoung.wechat.luckymoney.VersionParam.WECHAT_PACKAGE_NAME;
import static me.veryyoung.wechat.luckymoney.VersionParam.luckyMoneyReceiveUI;
import static me.veryyoung.wechat.luckymoney.VersionParam.receiveLuckyMoneyRequest;


public class Main implements IXposedHookLoadPackage {


    private static Object requestCaller;

    private static String wechatVersion = "";
    private static List<LuckyMoneyMessage> luckyMoneyMessages = new ArrayList<>();


    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(WECHAT_PACKAGE_NAME)) {
            if (isEmpty(wechatVersion)) {
                Context context = (Context) callMethod(callStaticMethod(findClass("android.app.ActivityThread", null), "currentActivityThread", new Object[0]), "getSystemContext", new Object[0]);
                String versionName = context.getPackageManager().getPackageInfo(lpparam.packageName, 0).versionName;
                log("Found wechat version:" + versionName);
                wechatVersion = versionName;
                new DonateHook().hook(lpparam);
                VersionParam.init(versionName);
            }
            findAndHookMethod("com.tencent.wcdb.database.SQLiteDatabase", lpparam.classLoader, "insert", String.class, String.class, ContentValues.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!PreferencesUtils.open()) {
                        return;
                    }

                    ContentValues contentValues = (ContentValues) param.args[2];
                    String tableName = (String) param.args[0];
                    if (TextUtils.isEmpty(tableName) || !tableName.equals("message")) {
                        return;
                    }
                    Integer type = contentValues.getAsInteger("type");
                    if (null == type) {
                        return;
                    }
                    if (type == 436207665 || type == 469762097) {

                        int status = contentValues.getAsInteger("status");
                        if (status == 4) {
                            return;
                        }

                        String talker = contentValues.getAsString("talker");

                        String blackList = PreferencesUtils.blackList();
                        if (!isEmpty(blackList)) {
                            for (String wechatId : blackList.split(",")) {
                                if (talker.equals(wechatId.trim())) {
                                    return;
                                }
                            }
                        }

                        int isSend = contentValues.getAsInteger("isSend");
                        if (PreferencesUtils.notSelf() && isSend != 0) {
                            return;
                        }


                        if (PreferencesUtils.notWhisper() && !isGroupTalk(talker)) {
                            return;
                        }

                        if (!isGroupTalk(talker) && isSend != 0) {
                            return;
                        }


                        String content = contentValues.getAsString("content");

                        String senderTitle = getFromXml(content, "sendertitle");
                        String notContainsWords = PreferencesUtils.notContains();
                        if (!isEmpty(notContainsWords)) {
                            for (String word : notContainsWords.split(",")) {
                                if (senderTitle.contains(word)) {
                                    return;
                                }
                            }
                        }

                        String nativeUrlString = getFromXml(content, "nativeurl");
                        Uri nativeUrl = Uri.parse(nativeUrlString);
                        int msgType = Integer.parseInt(nativeUrl.getQueryParameter("msgtype"));
                        int channelId = Integer.parseInt(nativeUrl.getQueryParameter("channelid"));
                        String sendId = nativeUrl.getQueryParameter("sendid");
                        requestCaller = callStaticMethod(findClass(VersionParam.networkRequest, lpparam.classLoader), VersionParam.getNetworkByModelMethod);

                        if (VersionParam.hasTimingIdentifier) {
                            callMethod(requestCaller, "a", newInstance(findClass(receiveLuckyMoneyRequest, lpparam.classLoader), channelId, sendId, nativeUrlString, 0, "v1.0"), 0);
                            luckyMoneyMessages.add(new LuckyMoneyMessage(msgType, channelId, sendId, nativeUrlString, talker));
                            return;
                        }
                        Object luckyMoneyRequest = newInstance(findClass(VersionParam.luckyMoneyRequest, lpparam.classLoader),
                                msgType, channelId, sendId, nativeUrlString, "", "", talker, "v1.0");

                        callMethod(requestCaller, "a", luckyMoneyRequest, getDelayTime());
                    }
                }
            });


            findAndHookMethod(receiveLuckyMoneyRequest, lpparam.classLoader, "a", int.class, String.class, JSONObject.class, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam param) throws JSONException {
                            if (!VersionParam.hasTimingIdentifier) {
                                return;
                            }

                            if (luckyMoneyMessages.size() <= 0) {
                                return;
                            }

                            String timingIdentifier = ((JSONObject) (param.args[2])).getString("timingIdentifier");
                            if (isEmpty(timingIdentifier)) {
                                return;
                            }
                            LuckyMoneyMessage luckyMoneyMessage = luckyMoneyMessages.get(0);
                            Object luckyMoneyRequest = newInstance(findClass(VersionParam.luckyMoneyRequest, lpparam.classLoader),
                                    luckyMoneyMessage.getMsgType(), luckyMoneyMessage.getChannelId(), luckyMoneyMessage.getSendId(), luckyMoneyMessage.getNativeUrlString(), "", "", luckyMoneyMessage.getTalker(), "v1.0", timingIdentifier);
                            callMethod(requestCaller, "a", luckyMoneyRequest, getDelayTime());
                            luckyMoneyMessages.remove(0);
                        }
                    }
            );

            findAndHookMethod(luckyMoneyReceiveUI, lpparam.classLoader, VersionParam.receiveUIFunctionName, int.class, int.class, String.class, VersionParam.receiveUIParamName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (PreferencesUtils.quickOpen()) {
                        Button button = (Button) findFirstFieldByExactType(param.thisObject.getClass(), Button.class).get(param.thisObject);
                        if (button.isShown() && button.isClickable()) {
                            button.performClick();
                        }
                    }
                }
            });

            findAndHookMethod("com.tencent.mm.plugin.profile.ui.ContactInfoUI", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (PreferencesUtils.showWechatId()) {
                        Activity activity = (Activity) param.thisObject;
                        ClipboardManager cmb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        String wechatId = activity.getIntent().getStringExtra("Contact_User");
                        cmb.setText(wechatId);
                        Toast.makeText(activity, "微信ID:" + wechatId + "已复制到剪切板", LENGTH_LONG).show();
                    }
                }
            });

            findAndHookMethod("com.tencent.mm.plugin.chatroom.ui.ChatroomInfoUI", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (PreferencesUtils.showWechatId()) {
                        Activity activity = (Activity) param.thisObject;
                        String wechatId = activity.getIntent().getStringExtra("RoomInfo_Id");
                        ClipboardManager cmb = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cmb.setText(wechatId);
                        Toast.makeText(activity, "微信ID:" + wechatId + "已复制到剪切板", LENGTH_LONG).show();
                    }
                }
            });

            new HideModule().hide(lpparam);
        }

    }

    private int getDelayTime() {
        int delayTime = 0;
        if (PreferencesUtils.delay()) {
            delayTime = getRandom(PreferencesUtils.delayMin(), PreferencesUtils.delayMax());
        }
        return delayTime;
    }

    private boolean isGroupTalk(String talker) {
        return talker.endsWith("@chatroom");
    }

    private String getFromXml(String xmlmsg, String node) throws XmlPullParserException, IOException {
        String xl = xmlmsg.substring(xmlmsg.indexOf("<msg>"));
        //nativeurl
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser pz = factory.newPullParser();
        pz.setInput(new StringReader(xl));
        int v = pz.getEventType();
        String result = "";
        while (v != XmlPullParser.END_DOCUMENT) {
            if (v == XmlPullParser.START_TAG) {
                if (pz.getName().equals(node)) {
                    pz.nextToken();
                    result = pz.getText();
                    break;
                }
            }
            v = pz.next();
        }
        return result;
    }

    private int getRandom(int min, int max) {
        return min + (int) (Math.random() * (max - min + 1));
    }


}
