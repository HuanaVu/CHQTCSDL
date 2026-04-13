package RTDRestaurant.View.Form.Staff_Form.Admin;

import RTDRestaurant.Controller.Service.Admin.ServiceAdmin;
import RTDRestaurant.Model.ModelCard;
import RTDRestaurant.Model.ModelChart;
import java.awt.Color;
import java.awt.Dimension;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;

public class RevenueReport_Form extends javax.swing.JPanel {

    private ServiceAdmin service;
    private DecimalFormat df;
    private SimpleDateFormat simpleDateFormat;
    private ArrayList<ModelChart> list;

    public RevenueReport_Form() {
        initComponents();
        init();
    }

    public void init() {
        service = new ServiceAdmin();
        df = new DecimalFormat("##,###,###");
        setCurrentDate();
        //Thêm data cho Card
        initCard("Hôm nay");
        //Thêm data cho Biểu đồ
        initChart();

    }

    public void initCard(String filter) {
        try {
            int revenue = 0;
            int expenses = 0;
            int profit = 0;
            int pre_revenue = 0;
            int pre_expenses = 0;
            int pre_profit = 0;
            String descR = ". . .";
            String descE = ". . .";
            String descP = ". . .";

            revenue = service.getRevenueHD(filter);
            expenses = service.getCostNK(filter);
            profit = revenue - expenses;

            if (filter.equals("Tháng này")) {
                pre_revenue = service.getPreMonthRevenueHD();
                pre_expenses = service.getPreMonthCostNK();
                pre_profit = pre_revenue - pre_expenses;
                descR = buildMonthlyTrendText(revenue, pre_revenue);
                descE = buildMonthlyTrendText(expenses, pre_expenses);
                descP = buildMonthlyTrendText(profit, pre_profit);
            }
            Crevenue.setData(new ModelCard(new ImageIcon(getClass().getResource("/Icons/revenue.png")), "Doanh Thu", df.format(revenue) + "đ", descR));
            Cexpenses.setData(new ModelCard(new ImageIcon(getClass().getResource("/Icons/expenses.png")), "Chi Phí", df.format(expenses) + "đ", descE));
            Cprofit.setData(new ModelCard(new ImageIcon(getClass().getResource("/Icons/profit.png")), "Lợi Nhuận", df.format(profit) + "đ", descP));

            Crevenue.repaint();
            Cexpenses.repaint();
            Cprofit.repaint();
        } catch (SQLException ex) {
            Logger.getLogger(RevenueReport_Form.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void initChart() {
        lineChart.addLegend("Doanh thu", new Color(101, 78, 163), new Color(101, 78, 163));
        lineChart.addLegend("Chi Phí", new Color(109, 222, 202), new Color(109, 222, 202));
        lineChart.addLegend("Lợi nhuận", new Color(35, 49, 64), new Color(35, 49, 64));
        try {
            list = service.getRevenueCostProfit_byMonth();
            for (ModelChart data : list) {
                lineChart.addData(data);
            }
        } catch (SQLException ex) {
            Logger.getLogger(RevenueReport_Form.class.getName()).log(Level.SEVERE, null, ex);
        }

        lineChart.start();
    }

    public void setCurrentDate() {
        simpleDateFormat = new SimpleDateFormat("dd-MM-yyyy");
        lbDate.setText("Ngày hiện tại: " + simpleDateFormat.format(new Date()));
    }

    private String buildMonthlyTrendText(int current, int previous) {
        if (previous <= 0) {
            if (current <= 0) {
                return "Không thay đổi so với tháng trước";
            }
            return "Tăng 100% so với tháng trước";
        }
        int percent = (int) Math.round((Math.abs((double) current - previous) / previous) * 100);
        if (current >= previous) {
            return "Tăng " + percent + "% so với tháng trước";
        }
        return "Giảm " + percent + "% so với tháng trước";
    }

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        lbTitle = new javax.swing.JLabel();
        lbprofit = new javax.swing.JLabel();
        panelCard = new javax.swing.JLayeredPane();
        Crevenue = new RTDRestaurant.View.Component.Admin_Component.Card();
        Cexpenses = new RTDRestaurant.View.Component.Admin_Component.Card();
        Cprofit = new RTDRestaurant.View.Component.Admin_Component.Card();
        lbDate = new javax.swing.JLabel();
        jSeparator2 = new javax.swing.JSeparator();
        jSeparator3 = new javax.swing.JSeparator();
        lbchart = new javax.swing.JLabel();
        lineChart = new RTDRestaurant.View.Swing.Chart.LineChart();
        filter = new javax.swing.JComboBox<>();

        setBackground(new java.awt.Color(247, 247, 247));

        lbTitle.setFont(new java.awt.Font("Segoe UI", 1, 28)); // NOI18N
        lbTitle.setForeground(new java.awt.Color(108, 91, 123));
        lbTitle.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Icons/analytics (1).png"))); // NOI18N
        lbTitle.setText("Báo cáo Doanh Thu");
        lbTitle.setIconTextGap(10);

        lbprofit.setFont(new java.awt.Font("Segoe UI", 1, 36)); // NOI18N
        lbprofit.setForeground(new java.awt.Color(89, 89, 89));
        lbprofit.setText("Thống kê Doanh Thu");

        panelCard.setMinimumSize(new java.awt.Dimension(120, 180));
        panelCard.setPreferredSize(new java.awt.Dimension(120, 180));
        panelCard.setLayout(new java.awt.GridLayout(1, 3, 12, 0));

        Crevenue.setColor1(new java.awt.Color(101, 78, 163));
        Crevenue.setColor2(new java.awt.Color(234, 175, 200));
        panelCard.add(Crevenue);

        Cexpenses.setColor1(new java.awt.Color(109, 222, 202));
        Cexpenses.setColor2(new java.awt.Color(137, 168, 203));
        panelCard.add(Cexpenses);

        Cprofit.setColor1(new java.awt.Color(35, 49, 64));
        Cprofit.setColor2(new java.awt.Color(61, 133, 144));
        panelCard.add(Cprofit);

        lbDate.setFont(new java.awt.Font("Segoe UI", 1, 30)); // NOI18N
        lbDate.setForeground(new java.awt.Color(108, 91, 123));
        lbDate.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Icons/calendar.png"))); // NOI18N
        lbDate.setText("Ngày hiện tại");
        lbDate.setIconTextGap(20);

        jSeparator2.setBackground(new java.awt.Color(76, 76, 76));

        jSeparator3.setBackground(new java.awt.Color(76, 76, 76));

        lbchart.setFont(new java.awt.Font("Segoe UI", 1, 36)); // NOI18N
        lbchart.setForeground(new java.awt.Color(89, 89, 89));
        lbchart.setText("Biểu Đồ Doanh Thu");

        filter.setEditable(false);
        filter.setFont(new java.awt.Font("Segoe UI", 0, 24)); // NOI18N
        filter.setForeground(new java.awt.Color(108, 91, 123));
        filter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Hôm nay", "Tháng này", "Năm này" }));
        filter.setToolTipText("Sắp xếp");
        filter.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(164, 145, 145), 2));
        filter.setFocusable(false);
        filter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                filterActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(lineChart, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(panelCard, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, 895, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                        .addComponent(lbTitle)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(lbDate, javax.swing.GroupLayout.PREFERRED_SIZE, 370, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jSeparator2, javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jSeparator3, javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(lbchart, javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lbprofit, javax.swing.GroupLayout.Alignment.LEADING))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(filter, javax.swing.GroupLayout.PREFERRED_SIZE, 190, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lbTitle)
                    .addComponent(lbDate))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSeparator2, javax.swing.GroupLayout.PREFERRED_SIZE, 1, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(filter, javax.swing.GroupLayout.DEFAULT_SIZE, 50, Short.MAX_VALUE)
                    .addComponent(lbprofit, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(panelCard, javax.swing.GroupLayout.PREFERRED_SIZE, 180, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(15, 15, 15)
                .addComponent(jSeparator3, javax.swing.GroupLayout.PREFERRED_SIZE, 1, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lbchart)
                .addGap(10, 10, 10)
                .addComponent(lineChart, javax.swing.GroupLayout.DEFAULT_SIZE, 370, Short.MAX_VALUE)
                .addContainerGap())
        );

            lineChart.setPreferredSize(new Dimension(900, 380));
    }// </editor-fold>//GEN-END:initComponents

    private void filterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_filterActionPerformed
        initCard(filter.getSelectedItem().toString());
    }//GEN-LAST:event_filterActionPerformed


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private RTDRestaurant.View.Component.Admin_Component.Card Cexpenses;
    private RTDRestaurant.View.Component.Admin_Component.Card Cprofit;
    private RTDRestaurant.View.Component.Admin_Component.Card Crevenue;
    private javax.swing.JComboBox<String> filter;
    private javax.swing.JSeparator jSeparator2;
    private javax.swing.JSeparator jSeparator3;
    private javax.swing.JLabel lbDate;
    private javax.swing.JLabel lbTitle;
    private javax.swing.JLabel lbchart;
    private javax.swing.JLabel lbprofit;
    private RTDRestaurant.View.Swing.Chart.LineChart lineChart;
    private javax.swing.JLayeredPane panelCard;
    // End of variables declaration//GEN-END:variables
}
