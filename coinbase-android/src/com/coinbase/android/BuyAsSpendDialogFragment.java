package com.coinbase.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

public class BuyAsSpendDialogFragment extends DialogFragment {

  public static BuyAsSpendDialogFragment newInstance(String amount) {
    final BuyAsSpendDialogFragment fragment = new BuyAsSpendDialogFragment();

    // Supply the amount as an argument.
    final Bundle args = new Bundle();
    args.putString("amount", amount);
    fragment.setArguments(args);

    return fragment;
  }

  protected String amount;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Bundle args = getArguments();
    if (args == null) {
      throw new IllegalStateException("No args");
    }

    amount = args.getString("amount");
    if (amount == null) {
      throw new IllegalStateException("amount arg is not present");
    }
  }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
      final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setTitle(R.string.buy_as_spend_dialog_title);
      builder.setMessage(String.format(getString(R.string.buy_as_spend_dialog_message), amount));
      builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.startBuyAsSpendWith(amount);
          }
      })
      .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
          // User cancelled the dialog
        }
      });

      return builder.create();
    }
}
