import { Pressable, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import Icon from "@react-native-vector-icons/ionicons";
import { AlertCircle, CheckCircle, Download } from "lucide-react-native";

import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { Text } from "~/components/ui/text";
import { useIconColor } from "~/hooks/useTheme";
import { useExportDatabase } from "~/hooks/useExportDatabase";
import { COLORS } from "~/lib/styleConstants";

const ExportDatabaseScreen = () => {
  const navigation = useNavigation();
  const iconColor = useIconColor();
  const { isExporting, showExportSuccess, showExportError, exportError, exportDatabase } =
    useExportDatabase();

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView contentContainerClassName="p-4 flex-grow">
        <View className="flex-row items-center mb-8">
          <Pressable onPress={() => navigation.goBack()} className="mr-4">
            <Icon name="arrow-back-outline" size={24} color={iconColor} />
          </Pressable>
          <Text className="text-2xl font-bold text-foreground">Export Database</Text>
        </View>

        {showExportSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Export Complete</AlertTitle>
            <AlertDescription>Your encrypted database backup was exported.</AlertDescription>
          </Alert>
        )}

        {showExportError && (
          <Alert icon={AlertCircle} variant="destructive" className="mb-4">
            <AlertTitle>Export Failed</AlertTitle>
            <AlertDescription>{exportError}</AlertDescription>
          </Alert>
        )}

        <View className="items-center rounded-2xl border border-border bg-card p-6">
          <View className="h-16 w-16 items-center justify-center rounded-2xl bg-orange-500/15">
            <Download size={32} color={COLORS.BITCOIN_ORANGE} />
          </View>
          <Text className="mt-5 text-center text-xl font-bold text-foreground">
            Export encrypted backup
          </Text>
          <Text className="mt-3 text-center text-base leading-6 text-muted-foreground">
            This creates an encrypted backup file containing a safe wallet snapshot. Keep this file
            secure, as it can be used to restore your wallet.
          </Text>
        </View>

        <NativeNoahButton
          label="Export Database"
          onPress={exportDatabase}
          isLoading={isExporting}
          loadingLabel="Exporting..."
          className="mt-8"
          fullWidth
        />
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default ExportDatabaseScreen;
