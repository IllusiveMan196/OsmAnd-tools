package net.osmand.live.subscriptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.json.JSONException;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver.Builder;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.InAppProduct;
import com.google.api.services.androidpublisher.model.InappproductsListResponse;
import com.google.api.services.androidpublisher.model.IntroductoryPriceInfo;
import com.google.api.services.androidpublisher.model.SubscriptionPurchase;
import com.google.gson.JsonObject;

import net.osmand.live.subscriptions.ReceiptValidationHelper.InAppReceipt;
import net.osmand.live.subscriptions.ReceiptValidationHelper.ReceiptResult;
import net.osmand.util.Algorithms;


public class UpdateSubscription {


	// init one time
	protected static final String GOOGLE_PRODUCT_NAME = "OsmAnd+";
	protected static final String GOOGLE_PRODUCT_NAME_FREE = "OsmAnd";

	private static final String GOOGLE_PACKAGE_NAME = "net.osmand.plus";
	private static final String GOOGLE_PACKAGE_NAME_FREE = "net.osmand";
	private static final int BATCH_SIZE = 200;
	private static final long DAY = 1000l * 60 * 60 * 24;
	private static final long HOUR = 1000l * 60 * 60;

	private static final long MINIMUM_WAIT_TO_REVALIDATE_VALID = 10 * DAY;
	private static final long MINIMUM_WAIT_TO_REVALIDATE = 12 * HOUR;
	private static final long MAX_WAITING_TIME_TO_EXPIRE = 15 * DAY;
	int changes = 0;
	int checkChanges = 0;
	int deletions = 0;
	protected String selQuery;
	protected String updQuery;
	protected String delQuery;
	protected String updCheckQuery;
	protected PreparedStatement updStat;
	protected PreparedStatement delStat;
	protected PreparedStatement updCheckStat;
	protected boolean ios;
	private AndroidPublisher publisher;
	
	private static class UpdateParams {
		public boolean verifyAll;
		public boolean verbose;
	}
	
	public UpdateSubscription(AndroidPublisher publisher, boolean ios, boolean revalidateInvalid) {
		this.ios = ios;
		this.publisher = publisher;
		// TODO use orderId and not purchaseToken
		delQuery = "UPDATE supporters_device_sub SET valid = false, kind = ?, checktime = ? "
				+ "WHERE purchaseToken = ? and sku = ?";
		updCheckQuery = "UPDATE supporters_device_sub SET checktime = ? "
				+ "WHERE purchaseToken = ? and sku = ?";
		String requestValid = "(valid is null or valid=true)";
		if (revalidateInvalid) {
			requestValid = "(valid=false)";
		}
		selQuery = "SELECT sku, purchaseToken, orderid, payload, checktime, starttime, expiretime, valid, introcycles "
				+ "FROM supporters_device_sub S where " + requestValid + " order by timestamp asc";
		if (ios) {
			updQuery = "UPDATE supporters_device_sub SET "
					+ " checktime = ?, orderid = ?, starttime = ?, expiretime = ?, autorenewing = ?, introcycles = ? , "
					+ " valid = ? " + " WHERE purchaseToken = ? and sku = ?";
		} else {
			updQuery = "UPDATE supporters_device_sub SET "
					+ " checktime = ?, orderid = ?, starttime = ?, expiretime = ?, autorenewing = ?, paymentstate = ?, "
					+ " kind = ?, payload = ?, "
					+ " price = ?, pricecurrency = ?, introprice = ?, intropricecurrency = ?, introcycles = ? , introcyclename = ?, "
					+ " valid = ? " + " WHERE purchaseToken = ? and sku = ?";
		}
	}

	public static void main(String[] args) throws JSONException, IOException, SQLException, ClassNotFoundException, GeneralSecurityException {
		AndroidPublisher publisher = getPublisherApi(args[0]);
//		if (true) {
//			test(publisher, "osm_live_subscription_annual_free_v2", args[1]);
//			return;
//		}

		Class.forName("org.postgresql.Driver");
		Connection conn = DriverManager.getConnection(System.getenv("DB_CONN"),
				System.getenv("DB_USER"), System.getenv("DB_PWD"));
		boolean android = true;
		boolean ios = true;
		boolean revalidateinvalid = false;
		UpdateParams up = new UpdateParams();
		for (int i = 1; i < args.length; i++) {
			if ("-verifyall".equals(args[i])) {
				up.verifyAll = true;
			} else if ("-verbose".equals(args[i])) {
				up.verbose = true;
			} else if ("-onlyandroid".equals(args[i])) {
				ios = false;
			} else if ("-revalidateinvalid".equals(args[i])) {
				revalidateinvalid = true;
			} else if ("-onlyios".equals(args[i])) {
				android = false;
			}
		}
		if (android) {
			new UpdateSubscription(publisher, false, revalidateinvalid).queryPurchases(conn, up);
		}
		if (ios) {
			new UpdateSubscription(null, true, revalidateinvalid).queryPurchases(conn, up);
		}
	}

	
	void queryPurchases(Connection conn, UpdateParams pms) throws SQLException {
		ResultSet rs = conn.createStatement().executeQuery(selQuery);
		updStat = conn.prepareStatement(updQuery);
		delStat = conn.prepareStatement(delQuery);
		updCheckStat = conn.prepareStatement(updCheckQuery);

		AndroidPublisher.Purchases purchases = publisher != null ? publisher.purchases() : null;
		ReceiptValidationHelper receiptValidationHelper = this.ios ? new ReceiptValidationHelper() : null;
		while (rs.next()) {
			String purchaseToken = rs.getString("purchaseToken");
			String sku = rs.getString("sku");
			String orderId = rs.getString("orderid");
			Timestamp checkTime = rs.getTimestamp("checktime");
			Timestamp startTime = rs.getTimestamp("starttime");
			Timestamp expireTime = rs.getTimestamp("expiretime");
			int introcycles = rs.getInt("introcycles");
			boolean valid = rs.getBoolean("valid");
			long tm = System.currentTimeMillis();
			boolean ios = sku.startsWith("net.osmand.maps.subscription.");
			boolean huawei = sku.contains("huawei");
			if (huawei) {
				// TODO not implemented yet (requires reports update etc)
				continue;
			}
			if (this.ios != ios) {
				continue;
			}
			long checkDiff = checkTime == null ? tm : (tm - checkTime.getTime());
			if (checkDiff < MINIMUM_WAIT_TO_REVALIDATE && !pms.verifyAll) {
				continue;
			}

			boolean activeNow = false;
			if (checkTime != null && startTime != null && expireTime != null) {
				if (expireTime.getTime() >= tm) {
					activeNow = true;
				}
			}
			// if it is not valid then it was requested to validate all
			if (activeNow && valid && checkDiff < MINIMUM_WAIT_TO_REVALIDATE_VALID) {
				continue;
			}
			String hiddenOrderId = orderId != null ? orderId.substring(0, Math.min(orderId.length(), 8)) : orderId;
			System.out.println(String.format("Validate subscriptin (%s, %s): %s - %s (active=%s)", sku, hiddenOrderId,
					startTime == null ? "" : new Date(startTime.getTime()),
					expireTime == null ? "" : new Date(expireTime.getTime()), activeNow + ""));
			if (this.ios) {
				processIosSubscription(receiptValidationHelper, purchaseToken, sku, orderId, startTime, expireTime, tm, introcycles, pms.verbose);
			} else {
				processAndroidSubscription(purchases, purchaseToken, sku, orderId, startTime, expireTime, tm, pms.verbose);
			}
		}
		if (deletions > 0) {
			delStat.executeBatch();
		}
		if (changes > 0) {
			updStat.executeBatch();
		}
		if (checkChanges > 0) {
			updCheckStat.executeBatch();
		}
		if (!conn.getAutoCommit()) {
			conn.commit();
		}
	}

	private void processIosSubscription(ReceiptValidationHelper receiptValidationHelper, String purchaseToken, String sku, String orderId, Timestamp startTime, Timestamp expireTime, long tm, 
			int prevIntroCycles, boolean verbose) throws SQLException {
		try {
			String reasonToDelete = null;
			String kind = "";
			ReceiptResult loadReceipt = receiptValidationHelper.loadReceipt(purchaseToken);
			if (loadReceipt.result) {
				JsonObject receiptObj = loadReceipt.response;
				if (verbose) {
					System.out.println("Result: " + receiptObj.toString());
				}
				try {
					List<InAppReceipt> inAppReceipts = ReceiptValidationHelper.parseInAppReceipts(receiptObj);
					if (inAppReceipts.isEmpty()) {
						kind = "empty";
						reasonToDelete = "empty in apps.";
					} else {
						boolean autoRenewing = false;
						int introCycles = 0;
						long startDate = 0;
						long expiresDate = 0;
						String appstoreOrderId = null;
						for (InAppReceipt receipt : inAppReceipts) {
							// there could be multiple subscriptions for same purchaseToken !
							// i.e. 2020-04-01 -> 2021-04-01 + 2021-04-05 -> 2021-04-05
							if (sku.equals(receipt.getProductId()) && (orderId == null || orderId.equals(receipt.getOrderId()))) {
								appstoreOrderId = receipt.getOrderId();
								Map<String, String> fields = receipt.fields;
								// purchase_date_ms is purchase date of prolongation
								boolean introPeriod = "true".equals(fields.get("is_in_intro_offer_period"));
								long inAppStartDateMs = Long.parseLong(fields.get("original_purchase_date_ms"));
								long inAppExpiresDateMs = Long.parseLong(fields.get("expires_date_ms"));
								if (inAppExpiresDateMs > expiresDate) {
									autoRenewing = receipt.autoRenew;
									expiresDate = inAppExpiresDateMs;
									startDate = inAppStartDateMs;
								}
								if (introPeriod) {
									introCycles++;
								}
							}
						}
						if (expiresDate > 0) {
							IntroductoryPriceInfo ipo = null;
							introCycles = Math.max(prevIntroCycles, introCycles);
							if (introCycles > 0) {
								ipo = new IntroductoryPriceInfo();
								ipo.setIntroductoryPriceCycles(introCycles);
							}
							SubscriptionPurchase subscription = new SubscriptionPurchase().setIntroductoryPriceInfo(ipo)
									.setStartTimeMillis(startDate).setExpiryTimeMillis(expiresDate)
									.setAutoRenewing(autoRenewing);
							if (!Algorithms.objectEquals(appstoreOrderId, orderId) && orderId != null) {
								throw new IllegalStateException(String.format("Order id '%s' != '%s' don't match", orderId, appstoreOrderId));
							}
							updateSubscriptionDb(purchaseToken, sku, appstoreOrderId, startTime, expireTime, tm, subscription);
							if (tm - expiresDate > MAX_WAITING_TIME_TO_EXPIRE) {
								kind = "gone";
								reasonToDelete = String.format("subscription expired more than %.1f days ago",
										(tm - expiresDate) / (DAY * 1.0d));

							}
						} else {
							kind = "empty";
							reasonToDelete = "no purchases purchase format.";
						}
					}
				} catch (NumberFormatException e) {
					e.printStackTrace();
					kind = "invalid";
					reasonToDelete = "wrong purchase format.";
				}

			}
			if (loadReceipt.error == ReceiptValidationHelper.SANDBOX_ERROR_CODE_TEST) {
				// sandbox: do not update anymore
				kind = "invalid";
				reasonToDelete = "receipt from sandbox environment";
			}
			if (loadReceipt.error == ReceiptValidationHelper.USER_GONE) {
				// sandbox: do not update anymore
				kind = "invalid";
				reasonToDelete = "user gone";
			}
			if (reasonToDelete != null) {
				deleteSubscription(purchaseToken, sku, tm, reasonToDelete, kind);
			}
		} catch (RuntimeException e) {
			System.err.println(String.format("?? Error updating  sku %s and orderid %s", sku, orderId, e.getMessage()));
			throw e;
		}
	}

	private void processAndroidSubscription(AndroidPublisher.Purchases purchases, String purchaseToken, String sku, String orderId, Timestamp startTime, Timestamp expireTime, long tm, boolean verbose) throws SQLException {
		SubscriptionPurchase subscription = null;
		try {
			if (sku.startsWith("osm_free") || sku.contains("_free_")) {
				subscription = purchases.subscriptions().get(GOOGLE_PACKAGE_NAME_FREE, sku, purchaseToken).execute();
			} else {
				subscription = purchases.subscriptions().get(GOOGLE_PACKAGE_NAME, sku, purchaseToken).execute();
			}
			if (verbose) {
				System.out.println("Result: " + subscription.toPrettyString());
			}
		} catch (IOException e) {
			boolean gone = false;
			if (e instanceof GoogleJsonResponseException) {
				gone = ((GoogleJsonResponseException) e).getStatusCode() == 410;
			}
			String reason = null;
			String kind = "";
			if (expireTime != null && tm - expireTime.getTime() > MAX_WAITING_TIME_TO_EXPIRE) {
				reason = String.format(" subscription expired more than %.1f days ago (%s)",
						(tm - expireTime.getTime()) / (DAY * 1.0d), e.getMessage());
				kind = "expired";
			} else if (!purchaseToken.contains(".AO")) {
				reason = "invalid purchase token (" + e.getMessage() + ") ";
				kind = "invalid";
			} else if (gone) {
				kind = "gone";
				reason = " user doesn't exist (" + e.getMessage() + ") ";
			}
			if (reason != null) {
				deleteSubscription(purchaseToken, sku, tm, reason, kind);
			} else {
				System.err.println(String.format("?? Error updating sku '%s' orderId '%s': %s", sku, orderId, e.getMessage()));
				int ind = 1;
				updCheckStat.setTimestamp(ind++, new Timestamp(tm));
				updCheckStat.setString(ind++, purchaseToken);
				updCheckStat.setString(ind++, sku);
				updCheckStat.addBatch();
				checkChanges++;
				if (checkChanges > BATCH_SIZE) {
					updCheckStat.executeBatch();
					checkChanges = 0;
				}
			}
		}
		if (subscription != null) {
			String appStoreOrderId = simplifyOrderId(subscription.getOrderId());
			if (!Algorithms.objectEquals(appStoreOrderId, orderId) && orderId != null) {
				throw new IllegalStateException(
						String.format("Order id '%s' != '%s' don't match", orderId, appStoreOrderId));
			}
			updateSubscriptionDb(purchaseToken, sku, appStoreOrderId, startTime, expireTime, tm, subscription);
		}
	}

	private String simplifyOrderId(String orderId) {
		int i = orderId.indexOf("..");
		if (i >= 0) {
			return orderId.substring(0, i);
		}
		return orderId;
	}

	private void deleteSubscription(String pt, String sku, long tm, String reason, String kind) throws SQLException {
		delStat.setString(1, kind);
		delStat.setTimestamp(2, new Timestamp(tm));
		delStat.setString(3, pt);
		delStat.setString(4, sku);
		delStat.addBatch();
		deletions++;
		System.out.println(String.format(
				"!! Clearing possible invalid subscription: sku=%s. Reason: %s ", sku, reason));
		if (deletions > BATCH_SIZE) {
			delStat.executeUpdate();
			deletions = 0;
		}
	}

	// TODO don't use purchaseToken !!!
	private void updateSubscriptionDb(String purchaseToken, String sku, String orderId, Timestamp startTime, Timestamp expireTime,
									  long tm, SubscriptionPurchase subscription) throws SQLException {
		boolean updated = false;
		int ind = 1;
		updStat.setTimestamp(ind++, new Timestamp(tm));
		// TODO Delete
		updStat.setString(ind++, orderId);
		if (subscription.getStartTimeMillis() != null) {
			if (startTime != null && Math.abs(startTime.getTime() - subscription.getStartTimeMillis()) > 14 * DAY && startTime.getTime() > 100000 * 1000L) {
				throw new IllegalArgumentException(String.format("ERROR: Start timestamp changed more than 14 days '%s' (db) != '%s' (appstore) '%s' %s",
						new Date(startTime.getTime()),
						new Date(subscription.getStartTimeMillis()), orderId, sku));
			}
			updStat.setTimestamp(ind++, new Timestamp(subscription.getStartTimeMillis()));
			updated = true;
		} else {
			updStat.setTimestamp(ind++, startTime);
		}
		if (subscription.getExpiryTimeMillis() != null) {
			if (expireTime == null || Math.abs(expireTime.getTime() - subscription.getExpiryTimeMillis()) > 10 * 1000) {
				System.out.println(String.format("Expire timestamp changed %s != %s for '%s' %s",
						expireTime == null ? "" : new Date(expireTime.getTime()),
						new Date(subscription.getExpiryTimeMillis()), orderId, sku));
			}
			updStat.setTimestamp(ind++, new Timestamp(subscription.getExpiryTimeMillis()));
			updated = true;
		} else {
			updStat.setTimestamp(ind++, expireTime);
		}
		if (subscription.getAutoRenewing() == null) {
			updStat.setNull(ind++, Types.BOOLEAN);
		} else {
			updStat.setBoolean(ind++, subscription.getAutoRenewing());
		}
		if (ios) {
			IntroductoryPriceInfo info = subscription.getIntroductoryPriceInfo();
			if (info != null) {
				updStat.setInt(ind++, (int) info.getIntroductoryPriceCycles());
			} else {
				updStat.setNull(ind++, Types.INTEGER);
			}
		} else {
			if (subscription.getPaymentState() == null) {
				updStat.setNull(ind++, Types.INTEGER);
			} else {
				updStat.setInt(ind++, subscription.getPaymentState());
			}
			updStat.setString(ind++, subscription.getKind());
			updStat.setString(ind++, subscription.getDeveloperPayload());
			updStat.setInt(ind++, (int) (subscription.getPriceAmountMicros() / 1000l));
			updStat.setString(ind++, subscription.getPriceCurrencyCode());
			IntroductoryPriceInfo info = subscription.getIntroductoryPriceInfo();
			if (info != null) {
				updStat.setInt(ind++, (int) (info.getIntroductoryPriceAmountMicros() / 1000l));
				updStat.setString(ind++, info.getIntroductoryPriceCurrencyCode());
				updStat.setInt(ind++, (int) info.getIntroductoryPriceCycles());
				updStat.setString(ind++, info.getIntroductoryPricePeriod());
			} else {
				updStat.setNull(ind++, Types.INTEGER);
				updStat.setNull(ind++, Types.VARCHAR);
				updStat.setNull(ind++, Types.INTEGER);
				updStat.setNull(ind++, Types.VARCHAR);
			}
		}
		boolean expired = tm - subscription.getExpiryTimeMillis() > MAX_WAITING_TIME_TO_EXPIRE;
		updStat.setBoolean(ind++, !expired);
		updStat.setString(ind++, purchaseToken);
		updStat.setString(ind, sku);
		System.out.println(String.format("%s %s start %s expire %s",
				updated ? "Updates " : "No changes ", sku,
				startTime == null ? "" : new Date(startTime.getTime()),
				expireTime == null ? "" : new Date(expireTime.getTime())
		));
		if (updated) {
			updStat.addBatch();
			changes++;
			if (changes > BATCH_SIZE) {
				updStat.executeBatch();
				changes = 0;
			}
		}
	}


	
	private static AndroidPublisher getPublisherApi(String file) throws JSONException, IOException, GeneralSecurityException {
		List<String> scopes = new ArrayList<String>();
		scopes.add("https://www.googleapis.com/auth/androidpublisher");
	    File dataStoreDir = new File(new File(file).getParentFile(), ".credentials");
	    JacksonFactory jsonFactory = new com.google.api.client.json.jackson2.JacksonFactory();
		
	    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(new FileInputStream(file)));
	    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

		// Build flow and trigger user authorization request.
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, clientSecrets, scopes)
						.setDataStoreFactory(new FileDataStoreFactory(dataStoreDir))
						.setAccessType("offline")
						.build();
		Builder bld = new LocalServerReceiver.Builder();
		bld.setPort(5000);
		Credential credential = new AuthorizationCodeInstalledApp(flow, bld.build()).authorize("user");
		System.out.println("Credentials saved to " + dataStoreDir.getAbsolutePath());		
		AndroidPublisher publisher = new AndroidPublisher.Builder(httpTransport, jsonFactory, credential)
				.setApplicationName(GOOGLE_PRODUCT_NAME).build();

		return publisher;
	}


	protected static void test(AndroidPublisher publisher, String subscriptionId, String purchaseToken) {
		try {
			com.google.api.services.androidpublisher.AndroidPublisher.Inappproducts.List lst = publisher.inappproducts().list(GOOGLE_PACKAGE_NAME_FREE);
			InappproductsListResponse response = lst.execute();
			for (InAppProduct p : response.getInappproduct()) {
				System.out.println("SKU=" + p.getSku() +
						" type=" + p.getPurchaseType() +
						" LNG=" + p.getDefaultLanguage() +
						//" P="+p.getPrices()+
						" Period=" + p.getSubscriptionPeriod() + " Status=" + p.getStatus());
			}
			if(subscriptionId.length() > 0 && purchaseToken.length() > 0) {
				AndroidPublisher.Purchases purchases = publisher.purchases();
				SubscriptionPurchase subscription = purchases.subscriptions().get(GOOGLE_PACKAGE_NAME_FREE, subscriptionId, purchaseToken).execute();
				System.out.println(subscription.getUnknownKeys());
				System.out.println(subscription.getAutoRenewing());
				System.out.println(subscription.getKind());
				System.out.println(new Date(subscription.getExpiryTimeMillis()));
				System.out.println(new Date(subscription.getStartTimeMillis()));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	

	

}
