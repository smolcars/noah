import { registerRootComponent } from "expo";
import "~/lib/pushNotifications";
import "~/lib/recurringBackgroundTask";
import "react-native-quick-base64";
import { initializeGT } from "gt-react-native";
import App from "./App";
import gtConfig from "./gt.config.json";
import { loadTranslations } from "./loadTranslations";

initializeGT({ ...gtConfig, loadTranslations });

// registerRootComponent calls AppRegistry.registerComponent('main', () => App);
// It also ensures that whether you load the app in Expo Go or in a native build,
// the environment is set up appropriately
registerRootComponent(App);
