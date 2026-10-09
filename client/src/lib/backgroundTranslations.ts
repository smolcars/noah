import { getLocale } from "gt-react-native";
import { getMessagesInternal } from "gt-i18n/internal";

// Native background tasks run without a GTProvider; use its persisted locale and bundled catalog.
export const getBackgroundMessages = () =>
  getMessagesInternal({ locale: getLocale(), enableI18n: true });
