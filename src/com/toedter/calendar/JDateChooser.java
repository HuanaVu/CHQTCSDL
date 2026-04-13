package com.toedter.calendar;

import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.Date;

/**
 * Stub implementation of JDateChooser for compilation purposes.
 * This is a minimal placeholder for the real JDateChooser library.
 */
public class JDateChooser extends JPanel {

    private Date date;
    private String dateFormatString = "dd/MM/yyyy";
    private DateEditor dateEditor;

    public JDateChooser() {
        this.date = new Date();
        this.dateEditor = new DateEditor();
    }

    public JDateChooser(Date date) {
        this.date = date != null ? date : new Date();
        this.dateEditor = new DateEditor();
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public Long getTime() {
        return date != null ? date.getTime() : null;
    }

    public void setTime(long milliseconds) {
        this.date = new Date(milliseconds);
    }

    public void setDateFormatString(String format) {
        this.dateFormatString = format;
    }

    public String getDateFormatString() {
        return dateFormatString;
    }

    public DateEditor getDateEditor() {
        return dateEditor;
    }

    public void setVisible(boolean visible) {
        super.setVisible(visible);
    }

    public boolean isEnabled() {
        return super.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
    }

    /**
     * Inner class to represent the date editor component
     */
    public static class DateEditor {
        private JTextField textField = new JTextField();

        public Object getUiComponent() {
            return textField;
        }
    }
}
