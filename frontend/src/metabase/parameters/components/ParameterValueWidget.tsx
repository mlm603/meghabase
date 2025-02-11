import { useDisclosure } from "@mantine/hooks";
import cx from "classnames";
import { useState } from "react";
import { t } from "ttag";

import { Sortable } from "metabase/core/components/Sortable";
import CS from "metabase/css/core/index.css";
import FormattedParameterValue from "metabase/parameters/components/FormattedParameterValue";
import S from "metabase/parameters/components/ParameterValueWidget.module.css";
import { ParameterValueWidgetTrigger } from "metabase/parameters/components/ParameterValueWidgetTrigger";
import { WidgetStatusIcon } from "metabase/parameters/components/WidgetStatusIcon";
import { getParameterIconName } from "metabase/parameters/utils/ui";
import { Box, Icon, Popover, type PopoverProps } from "metabase/ui";
import type Question from "metabase-lib/v1/Question";
import type { UiParameter } from "metabase-lib/v1/parameters/types";
import { getQueryType } from "metabase-lib/v1/parameters/utils/parameter-source";
import { isDateParameter } from "metabase-lib/v1/parameters/utils/parameter-type";
import {
  areParameterValuesIdentical,
  parameterHasNoDisplayValue,
} from "metabase-lib/v1/parameters/utils/parameter-values";
import type { Dashboard, ParameterId } from "metabase-types/api";

import { ParameterDropdownWidget } from "./ParameterDropdownWidget";

export type ParameterValueWidgetProps = {
  parameter: UiParameter;
  setValue: (value: any) => void;
  value: any;
  placeholder?: string;
  isEditing?: boolean;

  commitImmediately?: boolean;
  focusChanged?: (focused: boolean) => void;
  isFullscreen?: boolean;
  className?: string;
  parameters?: UiParameter[];
  dashboard?: Dashboard | null;
  question?: Question;
  setParameterValueToDefault?: (parameterId: ParameterId) => void;
  // This means the widget will take care of the default value.
  // Should be used for dashboards and native questions in the parameter bar,
  // Don't use in settings sidebars.
  enableRequiredBehavior?: boolean;
  mimicMantine?: boolean;
  isSortable?: boolean;
} & Partial<PopoverProps>;

export const ParameterValueWidget = ({
  className,
  commitImmediately = false,
  dashboard,
  enableRequiredBehavior,
  focusChanged,
  isEditing = false,
  isFullscreen,
  isSortable = false,
  mimicMantine,
  parameter,
  parameters,
  placeholder,
  question,
  setParameterValueToDefault,
  setValue,
  value,
  ...popoverProps
}: ParameterValueWidgetProps) => {
  const [isFocused, setIsFocused] = useState(false);

  const hasValue = !parameterHasNoDisplayValue(value);
  const noPopover = hasNoPopover(parameter);
  const parameterTypeIcon = getParameterIconName(parameter);
  const showTypeIcon = !isEditing && !hasValue && !isFocused;

  const [isOpen, { close, toggle }] = useDisclosure();

  const getOptionalActionIcon = () => {
    if (value != null) {
      return (
        <WidgetStatusIcon
          name="close"
          onClick={() => {
            setValue(null);
            close();
          }}
        />
      );
    }

    if (!hasNoPopover(parameter)) {
      return (
        <WidgetStatusIcon
          name="chevrondown"
          size={mimicMantine ? 16 : undefined}
        />
      );
    }
  };

  const getRequiredActionIcon = () => {
    const { required, default: defaultValue } = parameter;

    if (
      required &&
      defaultValue &&
      !areParameterValuesIdentical(wrapArray(value), wrapArray(defaultValue))
    ) {
      return (
        <WidgetStatusIcon
          name="time_history"
          onClick={() => setParameterValueToDefault?.(parameter.id)}
        />
      );
    }
  };

  const getActionIcon = () => {
    if (isFullscreen) {
      return null;
    }

    const icon =
      enableRequiredBehavior && parameter.required
        ? getRequiredActionIcon()
        : getOptionalActionIcon();

    if (!icon) {
      // This is required to keep input width constant
      return <WidgetStatusIcon name="empty" />;
    }

    return icon;
  };

  const resetToDefault = () => {
    const { required, default: defaultValue } = parameter;

    if (required && defaultValue && !value) {
      setValue(defaultValue);
    }
  };

  const onFocusChanged = (isFocused: boolean) => {
    focusChanged?.(isFocused);
    setIsFocused(isFocused);

    if (enableRequiredBehavior && !isFocused) {
      resetToDefault();
    }
  };

  if (noPopover) {
    return (
      <Sortable
        id={parameter.id}
        draggingStyle={{ opacity: 0.5 }}
        disabled={!isSortable}
        role="listitem"
      >
        <ParameterValueWidgetTrigger
          className={cx(S.noPopover, className)}
          hasValue={hasValue}
        >
          {showTypeIcon && (
            <Icon
              name={parameterTypeIcon}
              className={cx(CS.mr1, CS.flexNoShrink)}
              size={16}
            />
          )}
          <ParameterDropdownWidget
            parameter={parameter}
            parameters={parameters}
            question={question}
            dashboard={dashboard}
            value={value}
            setValue={setValue}
            isEditing={isEditing}
            placeholder={placeholder}
            focusChanged={setIsFocused}
            isFullscreen={isFullscreen}
            commitImmediately={commitImmediately}
            setParameterValueToDefault={setParameterValueToDefault}
            enableRequiredBehavior={enableRequiredBehavior}
            isSortable={isSortable}
            onFocusChanged={onFocusChanged}
          />
          {getActionIcon()}
        </ParameterValueWidgetTrigger>
      </Sortable>
    );
  }

  const placeholderText = isEditing
    ? isDateParameter(parameter)
      ? t`Select a default value…`
      : t`Enter a default value…`
    : placeholder || t`Select…`;

  return (
    <Popover
      opened={isOpen}
      onChange={toggle}
      position="bottom-start"
      {...popoverProps}
    >
      <Popover.Target>
        <Box onClick={toggle}>
          <Sortable
            id={parameter.id}
            draggingStyle={{ opacity: 0.5 }}
            disabled={!isSortable}
            role="listitem"
          >
            <ParameterValueWidgetTrigger
              hasValue={hasValue}
              className={className}
              ariaLabel={placeholder}
              mimicMantine={mimicMantine}
            >
              {showTypeIcon && (
                <Icon
                  name={parameterTypeIcon}
                  className={cx(CS.mr1, CS.flexNoShrink)}
                  size={16}
                />
              )}
              <div className={cx(CS.mr1, CS.textNoWrap)}>
                <FormattedParameterValue
                  parameter={parameter}
                  value={value}
                  placeholder={placeholderText}
                />
              </div>
              {getActionIcon()}
            </ParameterValueWidgetTrigger>
          </Sortable>
        </Box>
      </Popover.Target>
      <Popover.Dropdown data-testid="parameter-value-dropdown">
        <ParameterDropdownWidget
          parameter={parameter}
          parameters={parameters}
          question={question}
          dashboard={dashboard}
          value={value}
          setValue={setValue}
          isEditing={isEditing}
          placeholder={placeholder}
          focusChanged={setIsFocused}
          isFullscreen={isFullscreen}
          commitImmediately={commitImmediately}
          setParameterValueToDefault={setParameterValueToDefault}
          enableRequiredBehavior={enableRequiredBehavior}
          isSortable={isSortable}
          onFocusChanged={onFocusChanged}
          onPopoverClose={close}
        />
      </Popover.Dropdown>
    </Popover>
  );
};

function hasNoPopover(parameter: UiParameter) {
  // This is needed because isTextWidget check isn't complete,
  // and returns true for dates too.
  if (isDateParameter(parameter)) {
    return false;
  }
  return isTextWidget(parameter);
}

function isTextWidget(parameter: UiParameter) {
  const canQuery = getQueryType(parameter) !== "none";
  return parameter.hasVariableTemplateTagTarget && !canQuery;
}

function wrapArray(value: any): any[] {
  if (Array.isArray(value)) {
    return value;
  }
  return [value];
}
