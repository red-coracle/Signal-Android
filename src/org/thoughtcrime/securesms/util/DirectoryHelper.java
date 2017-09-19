package org.thoughtcrime.securesms.util;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.RecipientSettings;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingJoinedMessage;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class DirectoryHelper {

  private static final String TAG = DirectoryHelper.class.getSimpleName();

  public static void refreshDirectory(@NonNull Context context, @Nullable MasterSecret masterSecret)
      throws IOException
  {
    if (TextUtils.isEmpty(TextSecurePreferences.getLocalNumber(context))) return;

    RefreshResult result = refreshDirectory(context, AccountManagerFactory.createManager(context));

    if (!result.getNewUsers().isEmpty() && TextSecurePreferences.isMultiDevice(context)) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(context));
    }

    if (!result.isFresh()) {
      notifyNewUsers(context, masterSecret, result.getNewUsers());
    }
  }

  public static @NonNull RefreshResult refreshDirectory(@NonNull Context context, @NonNull SignalServiceAccountManager accountManager)
      throws IOException
  {
    if (TextUtils.isEmpty(TextSecurePreferences.getLocalNumber(context))) {
      return new RefreshResult(new LinkedList<>(), false);
    }

    RecipientDatabase recipientDatabase                       = DatabaseFactory.getRecipientDatabase(context);
    Stream<String>    eligibleRecipientDatabaseContactNumbers = Stream.of(recipientDatabase.getAllRecipients()).map(recipient -> recipient.getAddress().serialize());
    Stream<String>    eligibleSystemDatabaseContactNumbers    = Stream.of(ContactAccessor.getInstance().getAllContactsWithNumbers(context)).map(Address::serialize);
    Set<String>       eligibleContactNumbers                  = Stream.concat(eligibleRecipientDatabaseContactNumbers, eligibleSystemDatabaseContactNumbers).collect(Collectors.toSet());

    List<ContactTokenDetails> activeTokens = accountManager.getContacts(eligibleContactNumbers);

    if (activeTokens != null) {
      List<Recipient> activeRecipients   = new LinkedList<>();
      List<Recipient> inactiveRecipients = new LinkedList<>();

      Set<String>  inactiveContactNumbers = new HashSet<>(eligibleContactNumbers);

      for (ContactTokenDetails activeToken : activeTokens) {
        activeRecipients.add(Recipient.from(context, Address.fromSerialized(activeToken.getNumber()), true));
        inactiveContactNumbers.remove(activeToken.getNumber());
      }

      for (String inactiveContactNumber : inactiveContactNumbers) {
        inactiveRecipients.add(Recipient.from(context, Address.fromSerialized(inactiveContactNumber), true));
      }

      recipientDatabase.setRegistered(activeRecipients, inactiveRecipients);
      return updateContactsDatabase(context, Stream.of(activeRecipients).map(Recipient::getAddress).toList(), true);
    }

    return new RefreshResult(new LinkedList<>(), false);
  }

  public static RegisteredState refreshDirectoryFor(@NonNull  Context context,
                                                    @Nullable MasterSecret masterSecret,
                                                    @NonNull  Recipient recipient)
      throws IOException
  {
    RecipientDatabase             recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    SignalServiceAccountManager   accountManager    = AccountManagerFactory.createManager(context);
    String                        number            = recipient.getAddress().serialize();
    Optional<ContactTokenDetails> details           = accountManager.getContact(number);

    if (details.isPresent()) {
      recipientDatabase.setRegistered(recipient, RegisteredState.REGISTERED);

      RefreshResult result = updateContactsDatabase(context, Util.asList(recipient.getAddress()), false);

      if (!result.getNewUsers().isEmpty() && TextSecurePreferences.isMultiDevice(context)) {
        ApplicationContext.getInstance(context).getJobManager().add(new MultiDeviceContactUpdateJob(context));
      }

      if (!result.isFresh()) {
        notifyNewUsers(context, masterSecret, result.getNewUsers());
      }

      return RegisteredState.REGISTERED;
    } else {
      recipientDatabase.setRegistered(recipient, RegisteredState.NOT_REGISTERED);
      return RegisteredState.NOT_REGISTERED;
    }
  }

  private static @NonNull RefreshResult updateContactsDatabase(@NonNull Context context, @NonNull List<Address> activeAddresses, boolean removeMissing) {
    Optional<AccountHolder> account = getOrCreateAccount(context);

    if (account.isPresent()) {
      try {
        List<Address> newUsers = DatabaseFactory.getContactsDatabase(context)
                                                .setRegisteredUsers(account.get().getAccount(), activeAddresses, removeMissing);

        Cursor                                 cursor = ContactAccessor.getInstance().getAllSystemContacts(context);
        RecipientDatabase.BulkOperationsHandle handle = DatabaseFactory.getRecipientDatabase(context).resetAllDisplayNames();

        try {
          while (cursor != null && cursor.moveToNext()) {
            String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));

            if (!TextUtils.isEmpty(number)) {
              Address   address     = Address.fromExternal(context, number);
              Recipient recipient   = Recipient.from(context, address, true);
              String    displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));

              handle.setDisplayName(recipient, displayName);
            }
          }
        } finally {
          handle.finish();
        }

        return new RefreshResult(newUsers, account.get().isFresh());
      } catch (RemoteException | OperationApplicationException e) {
        Log.w(TAG, e);
      }
    }

    return new RefreshResult(new LinkedList<Address>(), false);
  }

  private static void notifyNewUsers(@NonNull  Context context,
                                     @Nullable MasterSecret masterSecret,
                                     @NonNull  List<Address> newUsers)
  {
    if (!TextSecurePreferences.isNewContactsNotificationEnabled(context)) return;

    for (Address newUser: newUsers) {
      if (!SessionUtil.hasSession(context, masterSecret, newUser) && !Util.isOwnNumber(context, newUser)) {
        IncomingJoinedMessage  message      = new IncomingJoinedMessage(newUser);
        Optional<InsertResult> insertResult = DatabaseFactory.getSmsDatabase(context).insertMessageInbox(message);

        if (insertResult.isPresent()) {
          int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
          if (hour >= 9 && hour < 23) {
            MessageNotifier.updateNotification(context, masterSecret, insertResult.get().getThreadId(), true);
          } else {
            MessageNotifier.updateNotification(context, masterSecret, insertResult.get().getThreadId(), false);
          }
        }
      }
    }
  }

  private static Optional<AccountHolder> getOrCreateAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account[]      accounts       = accountManager.getAccountsByType("org.thoughtcrime.securesms");

    Optional<AccountHolder> account;

    if (accounts.length == 0) account = createAccount(context);
    else                      account = Optional.of(new AccountHolder(accounts[0], false));

    if (account.isPresent() && !ContentResolver.getSyncAutomatically(account.get().getAccount(), ContactsContract.AUTHORITY)) {
      ContentResolver.setSyncAutomatically(account.get().getAccount(), ContactsContract.AUTHORITY, true);
    }

    return account;
  }

  private static Optional<AccountHolder> createAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account        account        = new Account(context.getString(R.string.app_name), "org.thoughtcrime.securesms");

    if (accountManager.addAccountExplicitly(account, null, null)) {
      Log.w(TAG, "Created new account...");
      ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
      return Optional.of(new AccountHolder(account, true));
    } else {
      Log.w(TAG, "Failed to create account!");
      return Optional.absent();
    }
  }

  private static class AccountHolder {

    private final boolean fresh;
    private final Account account;

    private AccountHolder(Account account, boolean fresh) {
      this.fresh   = fresh;
      this.account = account;
    }

    public boolean isFresh() {
      return fresh;
    }

    public Account getAccount() {
      return account;
    }

  }

  private static class RefreshResult {

    private final List<Address> newUsers;
    private final boolean       fresh;

    private RefreshResult(List<Address> newUsers, boolean fresh) {
      this.newUsers = newUsers;
      this.fresh = fresh;
    }

    public List<Address> getNewUsers() {
      return newUsers;
    }

    public boolean isFresh() {
      return fresh;
    }
  }

}
