package com.siriusapplications.coinbase;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Calendar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ListFragment;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.siriusapplications.coinbase.api.LoginManager;
import com.siriusapplications.coinbase.api.RpcManager;
import com.siriusapplications.coinbase.db.TransactionsDatabase;
import com.siriusapplications.coinbase.db.TransactionsDatabase.TransactionEntry;

public class TransactionsFragment extends ListFragment {

  private class LoadBalanceTask extends AsyncTask<Void, Void, String[]> {

    @Override
    protected String[] doInBackground(Void... params) {

      try {

        JSONObject balance = RpcManager.getInstance().callGet(getActivity(), "account/balance");

        return new String[] { balance.getString("amount"), balance.getString("currency") };

      } catch (IOException e) {

        e.printStackTrace();
      } catch (JSONException e) {

        e.printStackTrace();
      }

      return null;
    }

    @Override
    protected void onPreExecute() {

      mBalanceText.setTextColor(getResources().getColor(R.color.wallet_balance_color_invalid));
    }

    @Override
    protected void onPostExecute(String[] result) {

      if(result == null) {
        mBalanceText.setText(null);
        mBalanceText.setTextColor(getResources().getColor(R.color.wallet_balance_color_invalid));

        Toast.makeText(getActivity(), R.string.wallet_balance_error, Toast.LENGTH_SHORT).show();
      } else {

        BigDecimal balance = new BigDecimal(result[0]);
        DecimalFormat df = new DecimalFormat();
        df.setMaximumFractionDigits(4);
        df.setMinimumFractionDigits(4);
        df.setGroupingUsed(false);
        String balanceString = df.format(balance);

        mBalanceText.setTextColor(getResources().getColor(R.color.wallet_balance_color));
        mBalanceText.setText(String.format(getActivity().getString(R.string.wallet_balance), balanceString));
        mBalanceCurrency.setText(String.format(getActivity().getString(R.string.wallet_balance_currency), result[1]));

      }
    }

  }

  private class SyncTransactionsTask extends AsyncTask<Void, Void, Boolean> {

    @Override
    protected Boolean doInBackground(Void... params) {
      
      JSONObject response;
      
      try {

        response = RpcManager.getInstance().callGet(getActivity(), "transactions");
        
      } catch (IOException e) {
        Log.e("Coinbase", "I/O error refreshing transactions.");
        e.printStackTrace();

        return false;
      } catch (JSONException e) {
        // Malformed response from Coinbase.
        Log.e("Coinbase", "Could not parse JSON response from Coinbase, aborting refresh of transactions.");
        e.printStackTrace();

        return false;
      } 

      TransactionsDatabase dbHelper = new TransactionsDatabase(getActivity());
      SQLiteDatabase db = dbHelper.getWritableDatabase();

      db.beginTransaction();
      
      // Make API call to download list of transactions
      try {
        
        JSONArray transactionsArray = response.getJSONArray("transactions");

        // Remove all old transactions
        db.delete(TransactionEntry.TABLE_NAME, null, null);
        
        // Update user ID
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
        Editor editor = prefs.edit();
        editor.putString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), response.getJSONObject("current_user").getString("id"));
        editor.commit();

        for(int i = 0; i < transactionsArray.length(); i++) {

          JSONObject transaction = transactionsArray.getJSONObject(i).getJSONObject("transaction");
          ContentValues values = new ContentValues();
          
          String createdAtStr = transaction.optString("created_at", null);
          long createdAt;
          try {
            if(createdAtStr != null) {
              createdAt = ISO8601.toCalendar(createdAtStr).getTimeInMillis();
            } else {
              createdAt = -1;
            }
          } catch (ParseException e) {
            // Error parsing createdAt
            e.printStackTrace();
            createdAt = -1;
          }

          values.put(TransactionEntry._ID, transaction.getString("id"));
          values.put(TransactionEntry.COLUMN_NAME_JSON, transaction.toString());
          values.put(TransactionEntry.COLUMN_NAME_TIME, createdAt);

          db.insert(TransactionEntry.TABLE_NAME, null, values);
        }
        
        db.setTransactionSuccessful();

        // Update list
        //((CursorAdapter) mListView.getAdapter()).runQueryOnBackgroundThread(null);

        return true;

      } catch (JSONException e) {
        // Malformed response from Coinbase.
        Log.e("Coinbase", "Could not parse JSON response from Coinbase, aborting refresh of transactions.");
        e.printStackTrace();

        return false;
      } finally {
        
        db.endTransaction();
        db.close();
      }
    }

    @Override
    protected void onPostExecute(Boolean result) {

      // TODO Handle false value (meaning an error occurred).
    }

  }

  private String generateTransactionSummary(JSONObject t) throws JSONException {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

    String currentUserId = prefs.getString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), null);

    if(currentUserId != null &&
        t.optJSONObject("sender") != null &&
        currentUserId.equals(t.getJSONObject("sender").optString("id"))) {

      JSONObject r = t.optJSONObject("recipient");
      String recipientName = null;
      
      if(r == null) { 
        recipientName = getString(R.string.transaction_user_external);
      } else { 
        
        if("transfers@coinbase.com".equals(r.optString("email"))) {
          // This was a bitcoin sell
          return getString(R.string.transaction_summary_sell);
        }
        
        recipientName = r.optString("name", 
          r.optString("email", getString(R.string.transaction_user_external)));
      }

      if(t.getBoolean("request")) {
        return String.format(getString(R.string.transaction_summary_request_me), recipientName);
      } else {
        return String.format(getString(R.string.transaction_summary_send_me), recipientName);
      }
    } else {

      JSONObject r = t.optJSONObject("sender");
      String senderName = null;
      
      if(r == null) { 
        senderName = getString(R.string.transaction_user_external);
      } else { 
        
        if("transfers@coinbase.com".equals(r.optString("email"))) {
          // This was a bitcoin buy
          return getString(R.string.transaction_summary_buy);
        }
        
        senderName = r.optString("name", 
          r.optString("email", getString(R.string.transaction_user_external)));
      }

      if(t.getBoolean("request")) {
        return String.format(getString(R.string.transaction_summary_request_them), senderName);
      } else {
        return String.format(getString(R.string.transaction_summary_send_them), senderName);
      }
    }
  }

  private class TransactionViewBinder implements SimpleCursorAdapter.ViewBinder {

    @Override
    public boolean setViewValue(View arg0, Cursor arg1, int arg2) {

      try {
        JSONObject item = new JSONObject(new JSONTokener(arg1.getString(arg2)));

        switch(arg0.getId()) {

        case R.id.transaction_title:

          ((TextView) arg0).setText(generateTransactionSummary(item));
          return true;

        case R.id.transaction_amount: 

          BigDecimal balance = new BigDecimal(item.getJSONObject("amount").getString("amount"));
          DecimalFormat df = new DecimalFormat();
          df.setMaximumFractionDigits(4);
          df.setMinimumFractionDigits(4);
          df.setGroupingUsed(false);
          String balanceString = df.format(balance);
          
          int sign = balance.compareTo(BigDecimal.ZERO);
          int color = sign == -1 ? R.color.transaction_negative : (sign == 0 ? R.color.transaction_neutral : R.color.transaction_positive);

          ((TextView) arg0).setText(balanceString);
          ((TextView) arg0).setTextColor(getResources().getColor(color));
          return true;

        case R.id.transaction_currency: 

          ((TextView) arg0).setText(item.getJSONObject("amount").getString("currency"));
          return true;

        case R.id.transaction_status: 

          String status = item.optString("status", getString(R.string.transaction_status_error));

          String readable = status;
          int background = R.drawable.transaction_unknown;
          if("complete".equals(status)) {
            readable = getString(R.string.transaction_status_complete);
            background = R.drawable.transaction_complete;
          } else if("pending".equals(status)) {
            readable = getString(R.string.transaction_status_pending);
            background = R.drawable.transaction_pending;
          }

          ((TextView) arg0).setText(readable);
          ((TextView) arg0).setBackgroundResource(background);
          return true;
        }

        return false;
      } catch (JSONException e) {
        // Malformed transaction JSON.
        Log.e("Coinbase", "Corrupted database entry! " + arg1.getInt(arg1.getColumnIndex(TransactionEntry._ID)));
        e.printStackTrace();

        return true;
      }
    }
  }

  private class LoadTransactionsTask extends AsyncTask<Void, Void, Cursor> {

    @Override
    protected Cursor doInBackground(Void... params) {

      TransactionsDatabase database = new TransactionsDatabase(getActivity());
      SQLiteDatabase readableDb = database.getReadableDatabase();
      Cursor c = readableDb.query(TransactionsDatabase.TransactionEntry.TABLE_NAME,
          null, null, null, null, null, null);
      return c;
    }

    @Override
    protected void onPostExecute(Cursor result) {

      String[] from = { TransactionEntry.COLUMN_NAME_JSON, TransactionEntry.COLUMN_NAME_JSON,
          TransactionEntry.COLUMN_NAME_JSON, TransactionEntry.COLUMN_NAME_JSON };
      int[] to = { R.id.transaction_title, R.id.transaction_amount,
          R.id.transaction_status, R.id.transaction_currency };
      SimpleCursorAdapter adapter = new SimpleCursorAdapter(getActivity(), R.layout.fragment_transactions_item, result,
          from, to, 0);
      adapter.setViewBinder(new TransactionViewBinder());
      mListView.setAdapter(adapter);
    }
  }
  ListView mListView;
  TextView mBalanceText, mBalanceCurrency, mAccount;

  @Override
  public void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);

    // Refresh transactions when app started
    new SyncTransactionsTask().execute();
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {

    super.onSaveInstanceState(outState);

    outState.putString("balance_text", mBalanceText.getText().toString());
    outState.putString("balance_currency", mBalanceCurrency.getText().toString());
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {

    // Inflate base layout
    ViewGroup view = (ViewGroup) inflater.inflate(R.layout.fragment_transactions, container, false);

    mListView = (ListView) view.findViewById(android.R.id.list);

    // Inflate header (which contains account balance)
    View headerView = inflater.inflate(R.layout.fragment_transactions_header, null, false);
    view.addView(headerView, 0);

    mBalanceText = (TextView) headerView.findViewById(R.id.wallet_balance);
    mBalanceCurrency = (TextView) headerView.findViewById(R.id.wallet_balance_currency);
    mAccount = (TextView) headerView.findViewById(R.id.wallet_account);

    mAccount.setText(LoginManager.getInstance().getSelectedAccountName(getActivity()));

    if(savedInstanceState != null) {

      if(savedInstanceState.containsKey("balance_text")) {
        mBalanceText.setText(savedInstanceState.getString("balance_text"));
        mBalanceCurrency.setText(savedInstanceState.getString("balance_currency"));
      }
    }

    // Load transaction list
    new LoadTransactionsTask().execute();

    return view;
  }

  @Override
  public void onResume() {

    super.onResume();

    // Reload balance
    new LoadBalanceTask().execute();
  }

}
