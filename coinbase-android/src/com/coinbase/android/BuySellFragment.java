package com.coinbase.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.coinbase.android.Utils.CurrencyType;
import com.coinbase.android.pin.PINManager;
import com.coinbase.api.RpcManager;

import org.acra.ACRA;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class BuySellFragment extends Fragment implements CoinbaseFragment {

  private enum BuySellType {
    BUY(R.string.buysell_type_buy, "buy"),
    SELL(R.string.buysell_type_sell, "sell");

    private int mFriendlyName;
    private String mRequestType;

    private BuySellType(int friendlyName, String requestType) {

      mFriendlyName = friendlyName;
      mRequestType = requestType;
    }

    public String getRequestType() {

      return mRequestType;
    }

    public int getName() {

      return mFriendlyName;
    }

    public static BuySellType fromApiType(String apiType) {
      if("AchCredit".equals(apiType)) {
        return SELL;
      } else if("AchDebit".equals(apiType)) {
        return BUY;
      } else {
        return null;
      }
    }
  }

  public static class ConfirmBuySellDialogFragment extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

      final BuySellType type = (BuySellType) getArguments().getSerializable("type");
      final String amount1 = getArguments().getString("amount1");
      final boolean agreeBtcAmountVaries = getArguments().getBoolean("agreeBtcAmountVaries", false);

      final TextView message = new TextView(getActivity());
      message.setBackgroundColor(Color.WHITE);
      message.setTextColor(Color.BLACK);
      message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
      message.setLinksClickable(true);
      message.setMovementMethod(LinkMovementMethod.getInstance());

      float scale = getResources().getDisplayMetrics().density;
      int paddingPx = (int) (15 * scale + 0.5f);
      message.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

      if(agreeBtcAmountVaries) {

        message.setText(Html.fromHtml(getString(R.string.buysell_confirm_message_buy_varies)));
      } else {

        final String amount2 = getArguments().getString("amount2"),
            amount2currency = getArguments().getString("amount2currency");

        int messageResource = type == BuySellType.BUY ? R.string.buysell_confirm_message_buy : R.string.buysell_confirm_message_sell;
        message.setText(String.format(getString(messageResource), amount1, amount2, amount2currency));
      }

      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setView(message)
      .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {

          // Buy / sell!
          BuySellFragment parent = getActivity() == null ? null : ((MainActivity) getActivity()).getBuySellFragment();

          if(parent != null) {
            parent.startBuySellTask(type, amount1, agreeBtcAmountVaries);
          }
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

  private class DoBuySellTask extends AsyncTask<Object, Void, Object[]> {

    private static final String BTC_AMOUNT_VARIES_ERROR = "Sorry, the maximum number of purchases on Coinbase has been reached";
    private ProgressDialog mDialog;
    private boolean mAgreeBtcAmountVaries;

    @Override
    protected void onPreExecute() {

      super.onPreExecute();
      mDialog = ProgressDialog.show(mParent, null, getString(R.string.buysell_progress));
    }

    protected Object[] doInBackground(Object... params) {

      mAgreeBtcAmountVaries = params.length > 3 && params[3] != null;
      return doBuySell((BuySellType) params[0], (String) params[1], mAgreeBtcAmountVaries);
    }

    protected void onPostExecute(Object[] result) {

      try {
        mDialog.dismiss();
      } catch (Exception e) {
        // ProgressDialog has been destroyed already
      }

      boolean success = (Boolean) result[0];
      if(success) {

        BuySellType type = (BuySellType) result[2];

        int messageId = type == BuySellType.BUY ? R.string.buysell_success_buy : R.string.buysell_success_sell;
        String text = String.format(getString(messageId), (String) result[1]);
        Toast.makeText(mParent, text, Toast.LENGTH_SHORT).show();
        mAmount.setText(null);

        // Sync transactions
        mParent.refresh();

        // Hide keyboard (so the user can see their new transaction)
        InputMethodManager inputMethodManager = (InputMethodManager) mParent.getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(mParent.findViewById(android.R.id.content).getWindowToken(), 0);
      } else {

        if(result[1] != null && ((String) result[1]).trim().startsWith(BTC_AMOUNT_VARIES_ERROR)
            && !mAgreeBtcAmountVaries) {

          // Prompt to buy again, but with different message
          BuySellType type = (BuySellType) result[2];
          String amount = (String) result[3];
          ConfirmBuySellDialogFragment dialog = new ConfirmBuySellDialogFragment();
          Bundle b = new Bundle();
          b.putSerializable("type", type);
          b.putString("amount1", amount);
          b.putBoolean("agreeBtcAmountVaries", true);
          dialog.setArguments(b);
          dialog.show(getFragmentManager(), "confirm");
        } else {

          System.out.println("Does not start with '" + mAgreeBtcAmountVaries);
          Utils.showMessageDialog(getFragmentManager(), String.format(getString(R.string.buysell_error_api), (String) result[1]));
        }
      }
    }
  }

  private enum UpdatePriceFailure {
    ERROR_LOADING,
    NO_DATA_ENTERED;
  }

  private class UpdatePriceTask extends AsyncTask<String, Void, Object> {

    @Override
    protected void onPreExecute() {

      super.onPreExecute();
      mTotal.setText(null);
      mSubmitButton.setEnabled(false);
    }

    boolean mIsSingleUpdate;

    protected Object doInBackground(String... params) {

      try {

        String amount = params[0], type = params[1];

        mIsSingleUpdate = false;

        if(amount == null) {
          // Get single
          mIsSingleUpdate = true;
          amount = "1";
        }

        if(amount.isEmpty() || ".".equals(amount) || new BigDecimal(amount).doubleValue() == 0) {
          return UpdatePriceFailure.NO_DATA_ENTERED;
        }

        Collection<BasicNameValuePair> requestParams = new ArrayList<BasicNameValuePair>();
        requestParams.add(new BasicNameValuePair("qty", amount));

        JSONObject result = RpcManager.getInstance().callGet(mParent, "prices/" + type, requestParams);
        return result;

      } catch (IOException e) {

        e.printStackTrace();
      } catch (JSONException e) {

        ACRA.getErrorReporter().handleException(new RuntimeException("UpdatePrice", e));
        e.printStackTrace();
      }

      return UpdatePriceFailure.ERROR_LOADING;
    }

    protected void onPostExecute(Object result) {

      TextView target = mTotal;

      int format = mIsSingleUpdate ? R.string.buysell_type_price : R.string.buysell_total;
      int error = mIsSingleUpdate ? R.string.buysell_type_price_error : R.string.buysell_total_error;

      if(target != null) {

        if(result instanceof UpdatePriceFailure) {

          if(result == UpdatePriceFailure.ERROR_LOADING) {
            target.setVisibility(View.VISIBLE);
            target.setText(error);
          } else if(result == UpdatePriceFailure.NO_DATA_ENTERED) {
            target.setText(null);
            target.setVisibility(View.GONE);
          }
        } else {
          JSONObject json = (JSONObject) result;
          String subtotalAmount = Utils.formatCurrencyAmount(new BigDecimal(json.optJSONObject("subtotal").optString("amount")), false, CurrencyType.TRADITIONAL);
          String subtotalCurrency = json.optJSONObject("subtotal").optString("currency");

          if(mIsSingleUpdate) {

            updateLabelText(String.format(mParent.getString(format), subtotalAmount));
          } else {

            target.setVisibility(View.VISIBLE);
            String totalAmount = Utils.formatCurrencyAmount(new BigDecimal(json.optJSONObject("total").optString("amount")), false, CurrencyType.TRADITIONAL);
            String totalCurrency = json.optJSONObject("total").optString("currency");

            mSubmitButton.setEnabled(true);
            mCurrentPrice = totalAmount;
            mCurrentPriceCurrency = totalCurrency;

            // Create breakdown of transaction
            StringBuffer breakdown = new StringBuffer();
            JSONArray fees = json.optJSONArray("fees");

            breakdown.append("<font color=\"#757575\">");
            breakdown.append("Subtotal: $" + subtotalAmount + " " + subtotalCurrency + "<br>");

            for(int i = 0; i < fees.length(); i++) {
              JSONObject fee = fees.optJSONObject(i);
              String type = (String) fee.keys().next();
              String amount = fee.optJSONObject(type).optString("amount");
              String currency = fee.optJSONObject(type).optString("currency");
              breakdown.append(type.substring(0, 1).toUpperCase(Locale.CANADA)).append(type.substring(1)).append(" fee: $");
              breakdown.append(Utils.formatCurrencyAmount(new BigDecimal(amount), false, CurrencyType.TRADITIONAL));
              breakdown.append(' ').append(currency).append("<br>");
            }

            breakdown.append("</font>");
            breakdown.append("Total: $" + totalAmount + " " + totalCurrency);

            target.setText(Html.fromHtml(breakdown.toString()));
          }

          return;
        }
      }

      if(!mIsSingleUpdate) {
        mCurrentPrice = null;
        mSubmitButton.setEnabled(false);
      }
    }
  }

  private MainActivity mParent;

  private UpdatePriceTask mUpdatePriceTask, mUpdateSinglePriceTask;
  private String mCurrentPrice, mCurrentPriceCurrency;

  private TextView mTotal, mText2, mTypeBuy, mTypeSell;
  private Button mSubmitButton;
  private EditText mAmount;

  private ViewGroup mRootView;
  private RelativeLayout mHeader;
  private FrameLayout mListHeaderContainer;
  private BuySellType mBuySellType = BuySellType.BUY;

  @Override
  public void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
  }


  @Override
  public void onAttach(Activity activity) {

    super.onAttach(activity);
    mParent = (MainActivity) activity;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {

    // Inflate the layout for this fragment
    View view = inflater.inflate(R.layout.fragment_buysell, container, false);
    mRootView = (ViewGroup) view;
    mHeader = (RelativeLayout) view.findViewById(R.id.buysell_header);

    mTotal = (TextView) view.findViewById(R.id.buysell_total);
    mText2 = (TextView) view.findViewById(R.id.buysell_text2);
    mTypeBuy = (TextView) view.findViewById(R.id.buysell_type_buy);
    mTypeSell = (TextView) view.findViewById(R.id.buysell_type_sell);

    //mTotal.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));

    mTypeBuy.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        switchType(BuySellType.BUY);
      }
    });
    mTypeSell.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        switchType(BuySellType.SELL);
      }
    });

    mSubmitButton = (Button) view.findViewById(R.id.buysell_submit);
    mSubmitButton.setTypeface(FontManager.getFont(mParent, "Roboto-Light"));
    mSubmitButton.setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        submit();
      }
    });

    mAmount = (EditText) view.findViewById(R.id.buysell_amount);
    mAmount.addTextChangedListener(new TextWatcher() {

      @Override
      public void afterTextChanged(Editable s) {
      }

      @Override
      public void beforeTextChanged(CharSequence s, int start, int count,
          int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {

        updatePrice();
      }
    });

    switchType(BuySellType.BUY);

    return view;
  }

  private void updateLabelText(String price) {

    TextView target = mBuySellType == BuySellType.BUY ? mTypeBuy : mTypeSell;
    TextView disableTarget = mBuySellType == BuySellType.BUY ? mTypeSell : mTypeBuy;

    String base = mParent.getString(mBuySellType == BuySellType.BUY ? R.string.buysell_type_buy : R.string.buysell_type_sell);
    String disableBase = mParent.getString(mBuySellType == BuySellType.BUY ? R.string.buysell_type_sell : R.string.buysell_type_buy);

    Typeface normal = Typeface.DEFAULT;
    Typeface light = FontManager.getFont(mParent, "Roboto-Light");

    // Target text
    SpannableStringBuilder targetText = new SpannableStringBuilder(base);

    if(price != null) {
      targetText.append(' ').append(price);
      targetText.setSpan(new CustomTypefaceSpan("sans-serift", light), base.length(), base.length() + price.length() + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    target.setText(targetText);

    // Disable text
    disableTarget.setText(disableBase);
  }

  private void onTypeChanged() {

    BuySellType type = mBuySellType;

    float buyWeight = type == BuySellType.BUY ? 1 : 0;
    float sellWeight = type == BuySellType.SELL ? 1 : 0;
    ((LinearLayout.LayoutParams) mTypeBuy.getLayoutParams()).weight = buyWeight;
    ((LinearLayout.LayoutParams) mTypeSell.getLayoutParams()).weight = sellWeight;

    // Remove prices from labels
    updateLabelText(null);

    // Swap views
    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mTypeBuy.getLayoutParams();
    LinearLayout parent = (LinearLayout) mTypeBuy.getParent();
    parent.removeView(mTypeBuy);
    parent.addView(mTypeBuy, type == BuySellType.BUY ? 0 : 1, params);

    // Text color
    TextView active = type == BuySellType.BUY ? mTypeBuy : mTypeSell;
    TextView inactive = type == BuySellType.BUY ? mTypeSell : mTypeBuy;
    active.setTextColor(getResources().getColor(R.color.buysell_type_active));
    inactive.setTextColor(getResources().getColor(R.color.buysell_type_inactive));

    int submitLabel = type == BuySellType.BUY ? R.string.buysell_submit_buy : R.string.buysell_submit_sell;
    mSubmitButton.setText(submitLabel);

    if(mUpdateSinglePriceTask != null) {
      mUpdateSinglePriceTask.cancel(true);
    }

    updateAllPrices();
  }

  private void updateAllPrices() {

    BuySellType type = mBuySellType;
    mUpdateSinglePriceTask = new UpdatePriceTask();
    Utils.runAsyncTaskConcurrently(mUpdateSinglePriceTask, null, type.getRequestType());

    updatePrice();
  }

  private void updatePrice() {

    BuySellType type = mBuySellType;

    if(mUpdatePriceTask != null) {

      mUpdatePriceTask.cancel(true);
    }

    mUpdatePriceTask = new UpdatePriceTask();
    mTotal.setVisibility(View.GONE);
    Utils.runAsyncTaskConcurrently(mUpdatePriceTask, mAmount.getText().toString(), type.getRequestType());
  }

  private void switchType(BuySellType newType) {

    mBuySellType = newType;
    onTypeChanged();
  }

  protected void startBuySellTask(BuySellType type, String amount, boolean agreeBtcAmountVaries) {

    Utils.runAsyncTaskConcurrently(new DoBuySellTask(), type, amount, agreeBtcAmountVaries);
  }

  private Object[] doBuySell(BuySellType type, String amount, boolean agreeBtcAmountVaries) {

    List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
    params.add(new BasicNameValuePair("qty", amount));

    if(type == BuySellType.BUY && agreeBtcAmountVaries) {
      params.add(new BasicNameValuePair("agree_btc_amount_varies", "true"));
    }

    try {
      JSONObject response = RpcManager.getInstance().callPost(mParent, type.getRequestType() + "s", params);

      boolean success = response.getBoolean("success");

      if(success) {

        return new Object[] { true, amount, type };
      } else {

        String errorMessage = response.getJSONArray("errors").optString(0);
        return new Object[] { false, errorMessage, type, amount };
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (JSONException e) {
      ACRA.getErrorReporter().handleException(new RuntimeException("doBuySell", e));
      e.printStackTrace();
    }

    // There was an exception
    return new Object[] { false, getString(R.string.buysell_error_exception), type, amount };
  }

  public void onTransactionsSynced() {

  }

  public void refresh() {
    // Refresh buy price
    updateAllPrices();
  }

  private void submit() {

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }

    ConfirmBuySellDialogFragment dialog = new ConfirmBuySellDialogFragment();

    Bundle b = new Bundle();

    BuySellType type = mBuySellType;
    b.putSerializable("type", type);

    b.putString("amount1", mAmount.getText().toString());
    b.putString("amount2", mCurrentPrice);
    b.putString("amount2currency", mCurrentPriceCurrency);

    dialog.setArguments(b);

    dialog.show(getFragmentManager(), "confirm");
  }

  @Override
  public void onSwitchedTo() {

    // Focus text field
    mAmount.requestFocus();

    updateAllPrices();
  }

  @Override
  public void onPINPromptSuccessfulReturn() {
    submit();
  }

  public void startBuyAsSpendTask(String amount) {
    startBuySellTask(BuySellType.BUY, amount, true);
    mParent.refresh();
    mParent.switchTo(MainActivity.FRAGMENT_INDEX_TRANSACTIONS);
  }
}
