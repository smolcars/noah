import { useGT, useLocaleSelector } from "gt-react-native";
import { NativeNoahPicker } from "~/components/ui/NativeNoahPicker";

export function LanguagePicker({ testID }: { testID: string }) {
  const gt = useGT();
  const { locale, locales, setLocale, getLocaleProperties } = useLocaleSelector();

  return (
    <NativeNoahPicker
      value={locale}
      options={locales.map((code) => ({
        value: code,
        label: getLocaleProperties(code).nativeName,
      }))}
      onValueChange={setLocale}
      accessibilityLabel={gt("Language", { $context: "App display language picker." })}
      testID={testID}
    />
  );
}
