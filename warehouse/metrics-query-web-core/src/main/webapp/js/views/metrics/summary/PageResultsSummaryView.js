define([
  'jquery',
  'jqueryui',
  'underscore',
  'backbone',
  'text!templates/metrics/metricsContainerTemplate.html'
], function($, jQueryUI, _, Backbone, template) {

  var PageResultsSummaryView = Backbone.View.extend({
    
    className: 'well margin-bottom-0',

    initialize: function() {
      _.bindAll(this);
      var that = this;
      
      this._id = (Math.floor(Math.random() * 900000) + 100000);
    },
    
    render: function() {
      
      this.$el.empty();
      var compiledTemplate = _.template(template, { loadingMsg: "Retrieving query page metrics..."} );
      this.$el.append(compiledTemplate);
      this._renderChart();
      
      return this;
    },
    
    _renderChart: function() {
      
      var thatElement = this.$el.find('.metrics-view-container');
      
      if ($(thatElement[0]).hasClass('hide'))
        return this;
                  
      if (this.chart)
        this.chart.destroy();
      
      var options     = this.chartOptions; 
      
      options.chart.renderTo = thatElement[0];
      options.chart.width    = thatElement.width();
      options.chart.height   = thatElement.height();
      
      this.chart = new Highcharts.Chart(options);
      
      return this;
    },
    
    _loading: function() {
      this.$el.find('.metrics-view-container').addClass('hide');
      this.$el.find('.metrics-container-error').addClass('hide');
      this.$el.find('.metrics-container-loading').removeClass('hide');
      this.$el.find('.metrics-empty-container').addClass('hide');
    },
    
    _error: function() {
      this.$el.find('.metrics-view-container').addClass('hide');
      this.$el.find('.metrics-container-error').removeClass('hide');
      this.$el.find('.metrics-container-loading').addClass('hide');
      this.$el.find('.metrics-empty-container').addClass('hide');
    },
    
    update: function(collection,interval) {
      return this._update(collection,interval);
    },
    
    _update: function(collection,interval) {
      var coptions = this.chartOptions;
      
      // Reset chart
      
      _.each(coptions.series, function(d,i) {
        d.data.length = 0;
      }, this);
      // end reset
      
      if (collection) {
        collection.each(function(d, i) {
          var date = d.get('beginTime');
          
          coptions.series[0].data.push([parseInt(date), d.get('MaximumPageResultSize')]);
          coptions.series[1].data.push([parseInt(date), d.get('MinimumPageResultSize')]);
          coptions.series[2].data.push([parseInt(date), d.get('TotalPageResultSize')]);
          coptions.series[3].data.push([parseInt(date), d.get('TotalPages')]);
          if (_.isNumber(d.get('TotalPages')) && d.get('TotalPages') > 0) coptions.series[4].data.push([parseInt(date), Math.floor(d.get('TotalPageResultSize') / d.get('TotalPages'))]);
        });
      }
      
      _.each(coptions.series, function(d,i) {
        d.data = _.sortBy(d.data, function(dd) {
          return dd[0];
        }, this);
      }, this);
      
      coptions.tooltip.xDateFormat = (interval % 864e5) == 0 ? '%Y-%m-%d' : '%Y-%m-%d %H:%M';
      
      this.$el.find('.metrics-view-container').removeClass('hide');
      this.$el.find('.metrics-container-error').addClass('hide');
      this.$el.find('.metrics-container-loading').addClass('hide');
      this.$el.find('.metrics-empty-container').addClass('hide');
      
      return this._renderChart();
    },
    
    chartOptions: {
      chart: {
        plotBackgroundColor: null,
        plotBorderWidth: null,
        plotShadow: false,
        type: 'areaspline'
      },
      title: { 
        text: 'Page Result Summary'
      },
      xAxis: {
        // maxPadding: 0.25,
        // minRange: 7 * 24 * 3600000,
        type: 'datetime',
        dateTimeLabelFormats: {
          month: '%e. %b',
          year: '%b'
        }
      },
      yAxis: {
        min: 0,
        minorTickInterval: null
      },
      series: [
        {
          pointInterval: this._interval,
          name: 'Max Page Result Size',
          data: []
        },
        {
          pointInterval: this._interval,
          name: 'Min Page Result Size',
          data: []
        },
        {
          pointInterval: this._interval,
          name: 'Total Page Result Size',
          data: []
        },
        {
          pointInterval: this._interval,
          name: 'Total Pages',
          data: []
        },
        {
          pointInterval: this._interval,
          name: 'Avg Page Result Size',
          data: []
        },
      ],
      tooltip: {
        xDateFormat: '%Y-%m-%d'
      },
      credits: {
        enabled: false
      },
      legend: {
        align: 'left',
        layout: 'vertical',
        verticalAlign: 'top',
        enabled: true,
        floating: true,
        x: 90,
        y: 45,
        backgroundColor: '#fff'
      }
    }
  });
  
  return PageResultsSummaryView;
});