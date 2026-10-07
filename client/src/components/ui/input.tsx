import * as React from "react";
import { TextInput } from "react-native";
import { cn } from "~/lib/utils";
import { useCSSVariable } from "uniwind";

function Input({ className, ...props }: React.ComponentProps<typeof TextInput>) {
  const mutedForeground = useCSSVariable("--color-muted-foreground");
  const isMultiline = props.multiline ?? false;

  return (
    <TextInput
      className={cn(
        "web:flex web:w-full rounded-md border border-input bg-background px-3 web:py-2 text-base lg:text-sm text-foreground placeholder:text-muted-foreground web:ring-offset-background file:border-0 file:bg-transparent file:font-medium web:focus-visible:outline-none web:focus-visible:ring-2 web:focus-visible:ring-ring web:focus-visible:ring-offset-2",
        props.editable === false && "opacity-50 web:cursor-not-allowed",
        className,
      )}
      placeholderTextColor={mutedForeground as string}
      textAlignVertical={props.textAlignVertical ?? (isMultiline ? "top" : "center")}
      {...props}
    />
  );
}

export { Input };
