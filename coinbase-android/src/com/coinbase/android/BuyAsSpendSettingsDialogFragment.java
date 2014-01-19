package com.coinbase.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;

import java.util.Arrays;
import java.util.List;

public class BuyAsSpendSettingsDialogFragment extends DialogFragment {

  private int mSelectedOption = 0;

  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {

    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

    Integer[] items = new Integer[] {
                                     R.string.account_android_buy_as_spend_dialog_enabled,
                                     R.string.account_android_buy_as_spend_dialog_disabled,
    };

    final List<Integer> itemsList = Arrays.asList(items);
    String[] itemsText = new String[items.length];
    for(int i = 0; i < items.length; i++) {
      itemsText[i] = getString(items[i]);
    }

    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
    final int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    final String buyAsSpendKey = String.format(Constants.KEY_ACCOUNT_BUY_AS_SPEND, activeAccount);
    final boolean buyAsSpendEnabled = prefs.getString(buyAsSpendKey, null) != null;

    mSelectedOption = buyAsSpendEnabled ?
            itemsList.indexOf(R.string.account_android_buy_as_spend_dialog_enabled) :
            itemsList.indexOf(R.string.account_android_buy_as_spend_dialog_disabled);

    builder.setSingleChoiceItems(itemsText, mSelectedOption, null);
    builder.setTitle(R.string.account_android_buy_as_spend);
    builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

      @Override
      public void onClick(DialogInterface dialog, int which) {

        Editor e = prefs.edit();
        mSelectedOption = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
        if(mSelectedOption == itemsList.indexOf(R.string.account_android_buy_as_spend_dialog_disabled)) {
          e.putString(buyAsSpendKey, null);
        } else {
          e.putString(buyAsSpendKey, "enabled");
        }

        e.commit();
        dialog.dismiss();
      }
    });

    builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

      @Override
      public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
      }
    });

    return builder.create();
  }

}
