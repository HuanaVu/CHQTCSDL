package com.toedter.calendar;

import javax.swing.JPanel;
import java.util.Date;

/**
 * Stub implementation of JDateChooser for compilation purposes.
 * This is a minimal placeholder for the real JDateChooser library.
 */
public class JDateChooser extends JPanel {

    private Date date;

    public JDateChooser() {
        this.date = new Date();
    }

    public JDateChooser(Date date) {
        this.date = date != null ? date : new Date();
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

    public void setVisible(boolean visible) {
        super.setVisible(visible);
    }

    public boolean isEnabled() {
        return super.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
    }
}
