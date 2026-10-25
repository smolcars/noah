import { createMMKV } from "react-native-mmkv";
import { Platform } from "react-native";
import RNFSTurbo from "react-native-fs-turbo";

const documentDirectory = RNFSTurbo.DocumentDirectoryPath;
export const mmkvPath =
  Platform.OS === "ios"
    ? `${documentDirectory.replace(/\/files$/, "")}/mmkv`
    : `${documentDirectory}/mmkv`;

// Ensure the directory exists before initializing
if (!RNFSTurbo.exists(mmkvPath)) {
  RNFSTurbo.mkdir(mmkvPath);
}

export const mmkv = createMMKV({
  id: "noah-wallet-storage",
  path: mmkvPath,
});
