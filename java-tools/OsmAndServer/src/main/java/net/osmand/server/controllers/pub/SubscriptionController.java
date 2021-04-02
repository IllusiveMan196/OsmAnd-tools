package net.osmand.server.controllers.pub;

import static net.osmand.server.api.services.ReceiptValidationService.NO_SUBSCRIPTIONS_FOUND_STATUS;
import static net.osmand.server.api.services.ReceiptValidationService.USER_NOT_FOUND_STATUS;

import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;

import net.osmand.live.subscriptions.ReceiptValidationHelper;
import net.osmand.live.subscriptions.ReceiptValidationHelper.InAppReceipt;
import net.osmand.server.api.repo.MapUserRepository;
import net.osmand.server.api.repo.MapUserRepository.MapUser;
import net.osmand.server.api.repo.OsmRecipientsRepository;
import net.osmand.server.api.repo.OsmRecipientsRepository.OsmRecipient;
import net.osmand.server.api.repo.SupportersDeviceSubscriptionRepository;
import net.osmand.server.api.repo.SupportersDeviceSubscriptionRepository.SupporterDeviceSubscription;
import net.osmand.server.api.repo.SupportersDeviceSubscriptionRepository.SupporterDeviceSubscriptionPrimaryKey;
import net.osmand.server.api.repo.SupportersRepository;
import net.osmand.server.api.repo.SupportersRepository.Supporter;
import net.osmand.server.api.services.ReceiptValidationService;
import net.osmand.server.utils.BTCAddrValidator;
import net.osmand.util.Algorithms;

@RestController
@RequestMapping("/subscription")
public class SubscriptionController {
    private static final Log LOG = LogFactory.getLog(SubscriptionController.class);

    private static final int TIMEOUT = 20000;

    private PrivateKey subscriptionPrivateKey;

    @Autowired
    private SupportersRepository supportersRepository;
    
    @Autowired
    private MapUserRepository mapUserRepository;
    
    @Autowired
    private OsmRecipientsRepository osmRecipientsRepository;
    
    @Autowired
    private SupportersDeviceSubscriptionRepository subscriptionsRepository;
    
    @Autowired
    private ReceiptValidationService validationService;
    
    private ObjectMapper jsonMapper = new ObjectMapper();


    private final RestTemplate restTemplate;

    @Autowired
    public SubscriptionController(RestTemplateBuilder builder) {
        this.restTemplate = builder.setConnectTimeout(TIMEOUT).setReadTimeout(TIMEOUT).build();
        String iosSubscriptionKey = System.getenv().get("IOS_SUBSCRIPTION_KEY");
		if (!Algorithms.isEmpty(iosSubscriptionKey)) {
			byte[] pkcs8EncodedKey = Base64.getDecoder().decode(System.getenv().get("IOS_SUBSCRIPTION_KEY"));
			try {
				KeyFactory factory = KeyFactory.getInstance("EC");
				subscriptionPrivateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8EncodedKey));
			} catch (NoSuchAlgorithmException e) {
				LOG.error(e.getMessage(), e);
			} catch (InvalidKeySpecException e) {
				LOG.error(e.getMessage(), e);
			}
		}
	}

    private String encodeCredentialsToBase64(String userName, String password) {
        Base64.Encoder encoder = Base64.getMimeEncoder();
        byte[] credentials = userName.concat(":").concat(password).getBytes();
        return encoder.encodeToString(credentials);
    }

    private String processOsmUsername(String userName) {
        int ind = userName.indexOf('\'');
        if (ind > -1) {
            return userName.substring(0, ind).substring(ind + 1);
        }
        return userName;
    }

    private HttpHeaders buildHeaders(String credentials) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.AUTHORIZATION, String.format("Basic %s", credentials));
        return headers;
    }

    private void authenticateUser(String credentials) {
        HttpHeaders headers = buildHeaders(credentials);
        makeRequest(headers);
    }

    private void makeRequest(HttpHeaders headers) {
        HttpEntity<String> requestEntity = new HttpEntity<>(null, headers);
        restTemplate.exchange("https://api.openstreetmap.org/api/0.6/user/details",
                HttpMethod.GET, requestEntity, String.class);
    }

    private ResponseEntity<String> ok(String body, Object... args) {
		return ResponseEntity.ok().body(String.format(body, args));
	}
    
    private ResponseEntity<String> error(String txt) {
    	// clients don't accept error requests (neither mobile, neither http)
    	return ResponseEntity.ok().body(String.format("{\"error\": \"%s.\"}", txt.replace('"', '\'')));
	}
    
    @PostMapping(path = {"/register_email", "/register_email.php"},
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> registerEmail(HttpServletRequest request) {
        MapUser mapUser = new MapUser();
        mapUser.aid = request.getParameter("aid");
        String remoteAddr = request.getRemoteAddr();
    	Enumeration<String> hs = request.getHeaders("X-Forwarded-For");
        if (hs != null && hs.hasMoreElements()) {
            remoteAddr = hs.nextElement();
        }
        if(Algorithms.isEmpty(mapUser.aid)) {
        	mapUser.aid = remoteAddr;
        }
        mapUser.email = request.getParameter("email");
        if(!validateEmail(mapUser.email)) {
        	throw new IllegalStateException(String.format("Email '%s' is not valid.", mapUser.email));
        }
        mapUser.os = request.getParameter("os");
        mapUser.updateTime = new Date();
        mapUser = mapUserRepository.save(mapUser);
        return ok("{\"email\": \"%s\", \"time\": \"%d\"}", mapUser.email, mapUser.updateTime.getTime());
    }
    
	private boolean validateEmail(String email) {
		if(Algorithms.isEmpty(email)) {
			return false;
		}
		if(!email.contains("@")) {
			return false;
		}
		return true;
	}

	private String userInfoAsJson(Supporter s) {
		String response = String.format(
				"{\"userid\": \"%d\", \"token\": \"%s\", \"visibleName\": \"%s\", \"email\": \"%s\", "
						+ "\"preferredCountry\": \"%s\"}", 
				s.userId, s.token, s.visibleName, s.userEmail, s.preferredRegion);
		return response;
	}

	private Map<String, String> userInfoAsMap(Supporter s) {
    	Map<String, String> res = new HashMap<>();
    	res.put("userid", "" + s.userId);
		res.put("token", s.token);
		res.put("visibleName", s.visibleName);
		res.put("email", s.userEmail);
		res.put("preferredCountry", s.preferredRegion);
		return res;
	}

	private String userShortInfoAsJson(Supporter s) {
		String response = String.format(
				"{\"userid\": \"%d\", \"visibleName\": \"%s\",\"preferredCountry\": \"%s\"}", 
				s.userId, s.visibleName, s.preferredRegion);
		return response;
	}

    

	@PostMapping(path = {"/register", "/register.php"},
        consumes =  MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces =  MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> register(HttpServletRequest request) {
        String email = request.getParameter("email");
        String visibleName = request.getParameter("visibleName");
        String preferredCountry = request.getParameter("preferredCountry");
        // no email validation cause client doesn't provide it 99%
        // avoid all nulls, empty, none
        boolean emailValid = email != null && email.contains("@");
        if(emailValid) {
        	Optional<Supporter> optionalSupporter = supportersRepository.findByUserEmail(email);
        	if (optionalSupporter.isPresent()) {
        		Supporter supporter = optionalSupporter.get();
        		return ResponseEntity.ok(userInfoAsJson(supporter));
        	}
        }
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        int token = tlr.nextInt(100000, 1000000);
        Supporter supporter = new Supporter();
        supporter.userId = null;
        supporter.os = request.getParameter("os");
        supporter.token = String.valueOf(token);
        supporter.visibleName = visibleName;
        supporter.userEmail = email;
        supporter.preferredRegion = preferredCountry;
        supporter = supportersRepository.saveAndFlush(supporter);
        return ResponseEntity.ok(userInfoAsJson(supporter));
    }

    @PostMapping(path = {"/update", "/update.php"},
            consumes =  MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces =  MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(HttpServletRequest request) {
    	String userId = request.getParameter("userid");
    	if (isEmpty(userId)) {
        	return error("Please validate user id.");
        }
    	String token = request.getParameter("token");
    	if (isEmpty(token)) {
        	return error("Token is not present: fix will be in OsmAnd 3.2");
        }
        Optional<Supporter> sup = supportersRepository.findById(Long.parseLong(userId));
        if(!sup.isPresent() ) {
        	return error("Couldn't find your user id: " + userId);
        }
        Supporter supporter = sup.get();
        if(!token.equals(supporter.token)) {
        	return error("Couldn't validate the token: " + token);
        }
        supporter.visibleName = request.getParameter("visibleName");
        supporter.userEmail = request.getParameter("email");
        supporter.preferredRegion = request.getParameter("preferredCountry");
        supporter = supportersRepository.save(supporter);
        return ResponseEntity.ok(userInfoAsJson(supporter));
    }

    @PostMapping(path = {"/register_osm", "/register_osm.php"},
            consumes =  MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces =  MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> registerOsm(HttpServletRequest request) {
        String bitcoinAddress = request.getParameter("bitcoin_addr");
        // empty means deleted (keep it)
        if (bitcoinAddress != null && bitcoinAddress.length() > 0 && 
        		!BTCAddrValidator.validate(bitcoinAddress) ) {
        	return error("Please validate bitcoin address.");
        }
        String osmUser = request.getParameter("osm_usr");
        if (isEmpty(osmUser)) {
        	return error("Please validate osm user.");
        }
        String osmPassword = request.getParameter("osm_pwd");
        if (isEmpty(osmPassword)) {
        	return error("Please validate osm pwd.");
        }
        String email = request.getParameter("email");
        if (isEmpty(email) || !email.contains("@")) {
        	return error("Please validate your email address.");
        }
        String username = processOsmUsername(osmUser);
        String credentials = encodeCredentialsToBase64(username, osmPassword);
        try {
            authenticateUser(credentials);
        } catch (Exception ex) {
            return error("Please validate osm user/pwd (couldn't authenticate): " + ex.getMessage());
        }
        OsmRecipient recipient = new OsmRecipient();
        recipient.osmId = osmUser;
        recipient.email = email;
        recipient.bitcoinAddress = bitcoinAddress;
        recipient.updateTime = new Date();
        recipient = osmRecipientsRepository.save(recipient);
        String response = String.format("{\"osm_user\": \"%s\", \"bitcoin_addr\": \"%s\", \"time\": \"%s\"}",
                recipient.osmId, recipient.bitcoinAddress, recipient.updateTime.getTime()+"");
        return ResponseEntity.ok(response);
    }

    private boolean isEmpty(String s) {
		return s == null || s.length() == 0;
	}
    
	@PostMapping(path = { "/ios-receipt-validate" })
	public ResponseEntity<String> validateIos(HttpServletRequest request) throws Exception {
		String receipt = request.getParameter("receipt");
		JsonObject receiptObj = validationService.loadReceiptJsonObject(receipt, false);
		if (receiptObj != null) {
			Map<String, Object> result = new HashMap<>();
			Map<String, InAppReceipt> inAppReceipts = validationService.loadInAppReceipts(receiptObj);
			if (inAppReceipts != null) {
				if (inAppReceipts.size() == 0) {
					result.put("eligible_for_introductory_price", "true");
					result.put("eligible_for_subscription_offer", "false");
					result.put("result", false);
					result.put("status", NO_SUBSCRIPTIONS_FOUND_STATUS);
					return ResponseEntity.ok(jsonMapper.writeValueAsString(result));
				} else {
					result.put("eligible_for_introductory_price",
							isEligibleForIntroductoryPrice(inAppReceipts.values()) ? "true" : "false");

					// update existing subscription purchaseToken
					for (InAppReceipt r : inAppReceipts.values()) {
						if (r.isSubscription()) {
							Optional<SupporterDeviceSubscription> subscription = subscriptionsRepository.findTopByOrderIdAndSkuOrderByTimestampDesc(r.getOrderId(), r.getProductId());
							if (subscription.isPresent()) {
								SupporterDeviceSubscription s = subscription.get();
								if (!Algorithms.objectEquals(s.purchaseToken, receipt)) {
									s.purchaseToken = receipt;
									subscriptionsRepository.saveAndFlush(s);
								}
							}
						}
					}

					List<Map<String, String>> activeSubscriptions = new ArrayList<>();
					Map<String, Object> validationResult = validationService.validateReceipt(receiptObj, activeSubscriptions);
					result.putAll(validationResult);
					result.put("eligible_for_subscription_offer",
							isEligibleForSubscriptionOffer(inAppReceipts.values(), activeSubscriptions) ? "true" : "false");

					return ResponseEntity.ok(jsonMapper.writeValueAsString(result));
				}
			}
		}
		return error("Cannot load receipt.");
	}

	// TODO 
	private Supporter restoreUserIdByOrderId(Map<String, Object> result, Set<String> orderIds) {
		Optional<SupporterDeviceSubscription> subscriptionOpt = subscriptionsRepository.findTopByOrderIdIn(orderIds);
		if (subscriptionOpt.isPresent()) {
			SupporterDeviceSubscription subscription = subscriptionOpt.get();
			Optional<Supporter> supporter = supportersRepository.findById(subscription.userId);
			if (supporter.isPresent()) {
				Supporter s = supporter.get();
				result.put("user", userInfoAsMap(s));
				return s;
			}
		}
		return null;
	}

	private boolean isEligibleForIntroductoryPrice(@NonNull Collection<InAppReceipt> inAppReceipts) {
		for (InAppReceipt receipt : inAppReceipts) {
			String isTrialPeriod = receipt.fields.get("is_trial_period");
			String isInIntroOfferPeriod = receipt.fields.get("is_in_intro_offer_period");
			if ("true".equals(isTrialPeriod) || "true".equals(isInIntroOfferPeriod)) {
				return false;
			}
		}
		return true;
	}

	private boolean isEligibleForSubscriptionOffer(@NonNull Collection<InAppReceipt> inAppReceipts, @NonNull List<Map<String, String>> activeSubscriptions) {
    	if (activeSubscriptions.size() == 0) {
			for (InAppReceipt receipt : inAppReceipts) {
				if (receipt.isSubscription()) {
					return true;
				}
			}
		}
    	return false;
	}

	@PostMapping(path = { "/ios-fetch-signatures" })
	public ResponseEntity<String> fetchSignaturesIos(HttpServletRequest request) throws Exception {
		String productIdentifiers = request.getParameter("productIdentifiers");
		if (!Algorithms.isEmpty(productIdentifiers)) {
			String userId = request.getParameter("userId");
			HashMap<String, Object> result = new HashMap<>();
			List<Map<String, String>> signatures = new ArrayList<>();
			String[] productIdentifiersArray = productIdentifiers.split(";");
			for (String productIdentifier : productIdentifiersArray) {
				String discountIdentifiers = request.getParameter(productIdentifier + "_discounts");
				if (!Algorithms.isEmpty(discountIdentifiers)) {
					String[] discountIdentifiersArray = discountIdentifiers.split(";");
					for (String discountIdentifier : discountIdentifiersArray) {
						Map<String, String> signatureObj = generateOfferSignature(productIdentifier, discountIdentifier, userId);
						if (signatureObj != null) {
							signatures.add(signatureObj);
						}
					}
				}
			}
			result.put("signatures", signatures);
			result.put("status", 0);
			return ResponseEntity.ok(jsonMapper.writeValueAsString(result));
		}
		return error("Product identifiers are not defined.");
	}

	@Nullable
	private Map<String, String> generateOfferSignature(String productIdentifier, String offerIdentifier, String userId) {
    	String appBundleId = ReceiptValidationHelper.IOS_MAPS_BUNDLE_ID;
		String keyIdentifier = System.getenv().get("IOS_SUBSCRIPTION_KEY_ID");
		String nonce = UUID.randomUUID().toString().toLowerCase();
		String timestamp = "" + System.currentTimeMillis() / 1000L;
		if (Algorithms.isEmpty(userId)) {
			userId = "00000";
		}
		String usernameHash = DigestUtils.md5Hex(userId);

		String source = appBundleId + '\u2063' + keyIdentifier + '\u2063' + productIdentifier + '\u2063' + offerIdentifier + '\u2063' + usernameHash + '\u2063' + nonce + '\u2063' + timestamp;
		String signatureBase64 = null;
		if (subscriptionPrivateKey != null) {
			try {
				Signature ecdsa = Signature.getInstance("SHA256withECDSA");
				ecdsa.initSign(subscriptionPrivateKey);
				ecdsa.update(source.getBytes("UTF-8"));
				byte[] signature = ecdsa.sign();
				signatureBase64 = URLEncoder.encode(Base64.getEncoder().encodeToString(signature), "UTF-8");
			} catch (Exception e) {
				LOG.error(e.getMessage(), e);
			}
		} else {
			LOG.error("Subscription private key is null " +
					"(generateOfferSignature" +
					" productIdentifier=" + productIdentifier +
					" offerIdentifier=" + offerIdentifier +
					" usernameHash=" + usernameHash + ")");
		}
		if (signatureBase64 != null) {
			Map<String, String> result = new HashMap<>();
			result.put("appBundleId", appBundleId);
			result.put("keyIdentifier", keyIdentifier);
			result.put("productIdentifier", productIdentifier);
			result.put("offerIdentifier", offerIdentifier);
			result.put("userId", userId);
			result.put("usernameHash", usernameHash);
			result.put("nonce", nonce);
			result.put("timestamp", timestamp);
			result.put("signature", signatureBase64);
			return result;
		}
		return null;
	}

	
	// Android sends
	//	parameters.put("userid", userId);
	//	parameters.put("sku", info.getSku());
	//	parameters.put("orderId", info.getOrderId());
	//	parameters.put("purchaseToken", info.getPurchaseToken());
	//	parameters.put("email", email);
	//	parameters.put("token", token);
	//	parameters.put("version", Version.getFullVersion(ctx));
	//	parameters.put("lang", ctx.getLanguage() + "");
	//	parameters.put("nd", ctx.getAppInitializer().getFirstInstalledDays() + "");
	//	parameters.put("ns", ctx.getAppInitializer().getNumberOfStarts() + "");
	//	parameters.put("aid", ctx.getUserAndroidId());
	// iOS sends
    // [params setObject:@"ios" forKey:@"os"];
    // [params setObject:userId forKey:@"userid"];
    // [params setObject:token forKey:@"token"];
    // [params setObject:sku forKey:@"sku"];
    // [params setObject:transactionId forKey:@"purchaseToken"];
    // [params setObject:receiptStr forKey:@"payload"];
    // [params setObject:email forKey:@"email"];
	@PostMapping(path = {"/purchased", "/purchased.php"})
	public ResponseEntity<String> purchased(HttpServletRequest request) {
//		// TODO store in supporters table order id, userid, email 
//		SupporterUser suser = new SupporterUser();
//		ResponseEntity<String> error = validateUserId(request, suser);
//		if (error != null) {
//			return error;
//		}
//		// 
		SupporterDeviceSubscription subscr = new SupporterDeviceSubscription();
		String userId = request.getParameter("userid");
		if (isEmpty(userId)) {
			return error("Please validate user id.");
		}
		subscr.userId = Long.parseLong(userId);
		subscr.sku = request.getParameter("sku");
		// it was mixed in early ios versions, so orderid was passed as purchaseToken and payload as purchaseToken;
		// "ios".equals(request.getParameter("purchaseToken")) || 
		boolean ios = Algorithms.isEmpty(request.getParameter("orderId"));
		subscr.purchaseToken = ios ? request.getParameter("payload") : request.getParameter("purchaseToken");
		subscr.orderId = ios ? request.getParameter("purchaseToken") : request.getParameter("orderId");
		subscr.timestamp = new Date();
		Optional<SupporterDeviceSubscription> subscrOpt = subscriptionsRepository.findById(
						new SupporterDeviceSubscriptionPrimaryKey(subscr.sku, subscr.orderId));
		if (subscrOpt.isPresent()) {
			if (subscr.purchaseToken != null) {
				SupporterDeviceSubscription deviceSubscription = subscrOpt.get();
				deviceSubscription.purchaseToken = subscr.purchaseToken;
				subscriptionsRepository.save(deviceSubscription);
			}
			return ResponseEntity.ok("{ \"res\" : \"OK\" }");
		}
		subscriptionsRepository.save(subscr);
		return ResponseEntity.ok("{ \"res\" : \"OK\" }");
//		return ResponseEntity.ok(!suser.tokenValid ? userShortInfoAsJson(suser.supporter)
//				: userInfoAsJson(suser.supporter));
	}
	
	// TODO 
	private class SupporterUser {
		Supporter supporter = null;
		boolean tokenValid = true;
	}

	// TODO 
	private ResponseEntity<String> validateUserId(HttpServletRequest request, SupporterUser suser) {
		String userId = request.getParameter("userid");

		if (isEmpty(userId)) {
			return error("Please validate user id.");
		}
		String token = request.getParameter("token");
		if (isEmpty(token)) {
			suser.tokenValid = false;
			LOG.warn("Token was not provided: " + toString(request.getParameterMap()));
			// return error("Token is not present: fix will be in OsmAnd 3.2");
		}
		Optional<Supporter> sup = supportersRepository.findById(Long.parseLong(userId));
		if (!sup.isPresent()) {
			return error("Couldn't find your user id: " + userId);
		}
		Supporter supporter = sup.get();

		if (token != null && !token.equals(supporter.token)) {
			suser.tokenValid = false;
			LOG.warn("Token failed validation: " + toString(request.getParameterMap()));
			// return error("Couldn't validate the token: " + token);
		}
		suser.supporter = supporter;
		return null;
	}

	private String toString(Map<String, String[]> parameterMap) {
		StringBuilder bld = new StringBuilder();
		for(String s : parameterMap.keySet()) {
			bld.append(" ").append(s).append("=").append(Arrays.toString(parameterMap.get(s)));
		}
		return bld.toString();
	}
}
