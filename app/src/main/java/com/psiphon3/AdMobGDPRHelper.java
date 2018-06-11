package com.psiphon3;

import android.app.AlertDialog;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.google.ads.consent.AdProvider;
import com.google.ads.consent.ConsentInfoUpdateListener;
import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.List;

public class AdMobGDPRHelper {
    private Context context;
    private boolean showBuyAdFree;
    private static GDPRDialog gdprDialog;
    private String[] publisherIds;

    public AdMobGDPRHelper(Context context, String[] publisherIds) {
        this.showBuyAdFree = false;
        this.context = context;
        this.publisherIds = publisherIds;
    }

    public void setShowBuyAdFree(boolean show) {
        this.showBuyAdFree = show;
    }

    public void presentGDPRConsentDialogIfNeeded() {
        ConsentInformation.getInstance(context).requestConsentInfoUpdate(publisherIds, new ConsentInfoUpdateListener() {
            @Override
            public void onConsentInfoUpdated(com.google.ads.consent.ConsentStatus consentStatus) {
                // User's consent status successfully updated.
                if(consentStatus == com.google.ads.consent.ConsentStatus.UNKNOWN
                        && ConsentInformation.getInstance(context).isRequestLocationInEeaOrUnknown()) {
                    gdprDialog = new GDPRDialog(context);
                    gdprDialog.show();
                }
            }

            @Override
            public void onFailedToUpdateConsentInfo(String errorDescription) {
                Utils.MyLog.d(errorDescription);
            }
        });
    }

    private class GDPRDialog {
        private AlertDialog alertDialog;
        private GDPRAdProvidersDialog gdprAdProvidersDialog;

        public GDPRDialog(final Context context) {
            AlertDialog.Builder builder;

            builder = new AlertDialog.Builder(context);

            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.admob_gdpr, null);

            alertDialog = builder
                    .setView(dialogView)
                    .setCancelable(false)
                    .setTitle(R.string.app_name_psiphon_pro)
                    .setIcon(R.drawable.ic_launcher)
                    .create();

            gdprAdProvidersDialog = new GDPRAdProvidersDialog(context);

            dialogView.findViewById(R.id.viewAdProviders)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            gdprAdProvidersDialog.show();
                        }
                    });

            dialogView.findViewById(R.id.btGDPRAgree)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ConsentInformation.getInstance(context).setConsentStatus(ConsentStatus.PERSONALIZED);
                            alertDialog.dismiss();
                        }
                    });

            dialogView.findViewById(R.id.btGDPRDisagree)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ConsentInformation.getInstance(context).setConsentStatus(ConsentStatus.NON_PERSONALIZED);
                            alertDialog.dismiss();
                        }
                    });

            if(showBuyAdFree) {
                dialogView.findViewById(R.id.btBuyAdFree)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                alertDialog.dismiss();
                                StatusActivity statusActivity = (StatusActivity) context;
                                statusActivity.onRateLimitUpgradeButtonClick(v);
                            }
                        });
            } else {
                dialogView.findViewById(R.id.btBuyAdFree).setVisibility(View.GONE);
            }
        }

        public void show() {
            alertDialog.show();
        }
    }

    private class GDPRAdProvidersDialog {
        private AlertDialog alertDialog;

        public GDPRAdProvidersDialog(final Context context) {
            AlertDialog.Builder builder;

            builder = new AlertDialog.Builder(context);

            LayoutInflater inflater = LayoutInflater.from(context);
            View dialogView = inflater.inflate(R.layout.admob_gdpr_ad_providers, null);
            final List<AdProvider> adProviders =
                    ConsentInformation.getInstance(context).getAdProviders();

            ListView lv = dialogView.findViewById(R.id.lv);
            lv.setAdapter(new ArrayAdapter<AdProvider>(context, android.R.layout.simple_list_item_1, adProviders) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    AdProvider ap = adProviders.get(position);
                    TextView tv = (TextView)convertView;

                    if (tv == null) {
                        LayoutInflater inflater = LayoutInflater.from(context);
                        tv = (TextView)inflater.inflate(android.R.layout.simple_list_item_1, null);
                    }
                    tv.setText(Html.fromHtml(String.format("<a href=\"%s\">%s</a>", ap.getPrivacyPolicyUrlString(),  ap.getName())));
                    tv.setMovementMethod(LinkMovementMethod.getInstance());
                    return tv;
                }
            });

            alertDialog = builder
                    .setView(dialogView)
                    .setCancelable(true)
                    .setTitle(R.string.app_name_psiphon_pro)
                    .setIcon(R.drawable.ic_launcher)
                    .setPositiveButton(R.string.abc_action_mode_done, null)
                    .create();
        }

        public void show () {
            alertDialog.show();
        }
    }
}
