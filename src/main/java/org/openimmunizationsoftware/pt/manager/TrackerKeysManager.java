package org.openimmunizationsoftware.pt.manager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.model.ReportProfile;
import org.openimmunizationsoftware.pt.model.TrackerKeys;
import org.openimmunizationsoftware.pt.model.TrackerKeysId;
import org.openimmunizationsoftware.pt.model.WebUser;

public class TrackerKeysManager {
  private static final String KEY_ID_APPLICATION = "APPLICATION";
  public static final String KEY_CORE_EMAIL_ON_ERROR = "core.email.on.error";
  public static final String KEY_CORE_EMAIL_ON_STARTUP = "core.email.on.startup";
  public static final String KEY_REPORT_CSS = "report.css";
  public static final String KEY_REPORT_DAILY_ENABLED = "report.daily.enabled";
  public static final String KEY_REPORT_DAILY_TIME = "report.daily.time";
  public static final String KEY_REPORT_LAST_REPORT_DATE_DESCRIPTION = "report.last_report.date_description";
  public static final String KEY_SYSTEM_EMAIL_DEBUG = "system.email.debug";
  public static final String KEY_SYSTEM_EMAIL_REPLY = "system.email.reply";
  public static final String KEY_SYSTEM_EMAIL_ENABLE = "system.email.enable";
  public static final String KEY_SYSTEM_EMAIL_SMTPS_PASSWORD = "system.email.smtps.password";
  public static final String KEY_SYSTEM_EMAIL_SMTPS_PORT = "system.email.smtps.port";
  public static final String KEY_SYSTEM_EMAIL_SMTPS_USERNAME = "system.email.smtps.username";
  public static final String KEY_SYSTEM_EMAIL_USE_SMTPS = "system.email.use.smtps";
  public static final String KEY_SYSTEM_EXTERNAL_URL = "system.external.url";
  public static final String KEY_SYSTEM_SMTP_ADDRESS = "system.smtp.address";
  public static final String KEY_STUDENT_OFFER_IMAGE_BASE_FOLDER = "student.offer.image.base.folder";
  public static final String KEY_TRACK_TIME = "track.time";
  public static final String KEY_DISPLAY_SIZE = "display.size";
  public static final String KEY_DISPLAY_COLOR = "display.color";
  public static final String KEY_TIME_ZONE = "time.zone";
  public static final String KEY_DATE_DISPLAY_FORMAT = "date.display.format";
  public static final String KEY_DATE_ENTRY_FORMAT = "date.entry.format";
  public static final String KEY_TIME_DISPLAY_FORMAT = "time.display.format";
  public static final String KEY_TIME_ENTRY_FORMAT = "time.entry.format";
  public static final String KEY_AI_PROJECT_REVIEW_MODEL = "ai.project.review.model";
  /** Sound when a meeting starts on the Focused Action page: off, chime, bell or rise. */
  public static final String KEY_FOCUS_MEETING_CHIME = "focus.meeting.chime";

  public static final String KEY_EMAIL_DEBUG = "system.email.debug";
  public static final String KEY_EMAIL_REPLY = "system.email.reply";
  public static final String KEY_EMAIL_PASSWORD = "system.email.smtps.password";
  public static final String KEY_EMAIL_SMTPS_PORT = "system.email.smtps.port";
  public static final String KEY_EMAIL_USERNAME = "system.email.smtps.username";
  public static final String KEY_EMAIL_USE_SMTPS = "system.email.use.smtps";

  public static final int KEY_TYPE_GLOBAL = 0;
  public static final int KEY_TYPE_APPLICATION = 1;
  public static final int KEY_TYPE_USER = 2;
  public static final int KEY_TYPE_REPORT = 5;

  public static final String KEY_ID_GLOBAL = "global";
  public static final String KEY_TEMPLATE_ADVANCE_DAYS = "TEMPLATE_ADVANCE_DAYS";

  public static String getKeyContent(String keyName, int keyType, String keyId,
      Session dataSession) {
    Query query = dataSession.createQuery(
        "from TrackerKeys where id.keyName = ? and id.keyType = ? and id.keyId = ? and keyContent is not null");
    query.setParameter(0, keyName);
    query.setParameter(1, keyType);
    query.setParameter(2, keyId);
    @SuppressWarnings("unchecked")
    List<TrackerKeys> trackerKeysList = query.list();
    if (trackerKeysList.size() > 0) {
      return new String(trackerKeysList.get(0).getKeyContent());
    }
    if (keyType == KEY_TYPE_APPLICATION) {
      return null;
    }
    return getKeyContent(keyName, KEY_TYPE_APPLICATION, "APPLICATION", dataSession);
  }

  public static String getReportKeyValue(String keyName, String defaultValue,
      ReportProfile reportProfile, Session dataSession) {
    return getKeyValue("SELECTOR_REPORT." + keyName, KEY_TYPE_REPORT,
        String.valueOf(reportProfile.getProfileId()), defaultValue, dataSession);
  }

  public static int getReportKeyValueInt(String keyName, String defaultValue,
      ReportProfile reportProfile, Session dataSession) {
    return Integer.parseInt(getKeyValue("SELECTOR_REPORT." + keyName, KEY_TYPE_REPORT,
        String.valueOf(reportProfile.getProfileId()), defaultValue, dataSession));
  }

  public static boolean getReportKeyValueBoolean(String keyName, String defaultValue,
      ReportProfile reportProfile, Session dataSession) {
    return makeBoolean(getKeyValue("SELECTOR_REPORT." + keyName, KEY_TYPE_REPORT,
        String.valueOf(reportProfile.getProfileId()), defaultValue, dataSession));
  }

  public static Date getReportKeyValueDateTime(String keyName, String defaultValue,
      ReportProfile reportProfile, Session dataSession) {
    return makeDateTime(getKeyValue("SELECTOR_REPORT." + keyName, KEY_TYPE_REPORT,
        String.valueOf(reportProfile.getProfileId()), defaultValue, dataSession));
  }

  private static Date makeDateTime(String value) {
    if (value != null) {
      SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
      try {
        return sdf.parse(value);
      } catch (ParseException pe) {
        return null;
      }
    }
    return null;
  }

  private static boolean makeBoolean(String value) {
    if (value != null && value.equalsIgnoreCase("Y")) {
      return true;
    }
    return false;
  }

  public static String getKeyValue(String keyName, int keyType, String keyId, String defaultValue,
      Session dataSession) {
    Query query = dataSession
        .createQuery("from TrackerKeys where id.keyName = ? and id.keyType = ? and id.keyId = ?");
    query.setParameter(0, keyName);
    query.setParameter(1, keyType);
    query.setParameter(2, keyId);
    @SuppressWarnings("unchecked")
    List<TrackerKeys> trackerKeysList = query.list();
    if (trackerKeysList.size() > 0) {
      String value = trackerKeysList.get(0).getKeyValue();
      if (value != null && !value.trim().equals("")) {
        return value;
      }
    } else {
      if (keyType == KEY_TYPE_USER || keyType == KEY_TYPE_REPORT) {
        return getKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION, defaultValue,
            dataSession);
      }
    }
    if (keyType == KEY_TYPE_USER || keyType == KEY_TYPE_REPORT) {
      return getKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION, defaultValue,
          dataSession);
    }
    return defaultValue;
  }

  public static String getApplicationKeyValue(String keyName, Session dataSession) {
    return getKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION, "", dataSession);
  }

  public static String getApplicationKeyValue(String keyName, String defaultValue,
      Session dataSession) {
    return getKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION, defaultValue,
        dataSession);
  }

  public static boolean getApplicationKeyValueBoolean(String keyName, boolean defaultValue,
      Session dataSession) {
    return makeBoolean(getKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION,
        defaultValue ? "Y" : "N", dataSession));
  }

  public static String getKeyValue(String keyName, String defaultValue, WebUser webUser,
      Session dataSession) {
    return getKeyValue(keyName, KEY_TYPE_USER, webUser.getUsername(), defaultValue, dataSession);
  }

  public static String getUserKeyValueNoFallback(String keyName, String defaultValue,
      WebUser webUser, Session dataSession) {
    Query query = dataSession
        .createQuery("from TrackerKeys where id.keyName = ? and id.keyType = ? and id.keyId = ?");
    query.setParameter(0, keyName);
    query.setParameter(1, KEY_TYPE_USER);
    query.setParameter(2, webUser.getUsername());
    @SuppressWarnings("unchecked")
    List<TrackerKeys> trackerKeysList = query.list();
    if (trackerKeysList.size() > 0) {
      String value = trackerKeysList.get(0).getKeyValue();
      if (value != null && !value.trim().equals("")) {
        return value;
      }
    }
    return defaultValue;
  }

  public static boolean getUserKeyValueBooleanNoFallback(String keyName, boolean defaultValue,
      WebUser webUser, Session dataSession) {
    return makeBoolean(getUserKeyValueNoFallback(keyName, defaultValue ? "Y" : "N", webUser,
        dataSession));
  }

  public static void saveKeyValue(String keyName, WebUser webUser, String value,
      Session dataSession) {
    saveKeyValue(keyName, KEY_TYPE_USER, webUser.getUsername(), value, dataSession);
  }

  public static void saveApplicationKeyValue(String keyName, String value, Session dataSession) {
    saveKeyValue(keyName, KEY_TYPE_APPLICATION, KEY_ID_APPLICATION, value, dataSession);
  }

  public static void saveReportKeyValue(String keyName, ReportProfile reportProfile, String value,
      Session dataSession) {
    saveKeyValue("SELECTOR_REPORT." + keyName, KEY_TYPE_REPORT,
        String.valueOf(reportProfile.getProfileId()), value, dataSession);
  }

  public static void saveKeyValue(String keyName, int keyType, String keyId, String value,
      Session dataSession) {
    Query query = dataSession
        .createQuery("from TrackerKeys where id.keyName = ? and id.keyType = ? and id.keyId = ?");
    query.setParameter(0, keyName);
    query.setParameter(1, keyType);
    query.setParameter(2, keyId);
    @SuppressWarnings("unchecked")
    List<TrackerKeys> trackerKeysList = query.list();
    if (trackerKeysList.size() > 0) {
      TrackerKeys trackerKeys = trackerKeysList.get(0);
      trackerKeys.setKeyValue(value);
      Transaction trans = dataSession.beginTransaction();
      dataSession.update(trackerKeys);
      trans.commit();
    } else {
      String currentValue = getKeyValue(keyName, keyType, keyId, null, dataSession);
      if (currentValue == null || !currentValue.equals(value)) {
        TrackerKeys trackerKeys = new TrackerKeys();
        TrackerKeysId trackerKeysId = new TrackerKeysId();
        trackerKeysId.setKeyId(keyId);
        trackerKeysId.setKeyType(keyType);
        trackerKeysId.setKeyName(keyName);
        trackerKeys.setId(trackerKeysId);
        trackerKeys.setKeyValue(value);
        Transaction trans = dataSession.beginTransaction();
        dataSession.save(trackerKeys);
        trans.commit();
      }
    }
  }
}
